(ns partymanager.websocket
  (:require
   [cheshire.core :as json]
   [clojure.tools.logging :as log]
   [org.httpkit.server :as http-kit]
   [partymanager.state :as state])
  (:import
   (java.util UUID)))

(defonce clients (atom {}))

(defn notify-clients
  "Send updated parties data to all connected clients"
  [parties]
  (log/info "Notifying clients about parties update. Parties:" parties)
  (doseq [[client-id {:keys [channel user-id]}] @clients]
    (let [_ (log/info "Processing notification for client:" client-id "user:" user-id)
          filtered-parties (state/filter-user-parties parties user-id)]
      (log/info "Sending update to client" client-id "user" user-id "filtered parties:" filtered-parties)
      (http-kit/send! channel
                      (json/generate-string
                       {:type "parties-update"
                        :parties filtered-parties})))))

(defn send-error
  "Send error message to specific client"
  [channel error-msg]
  (http-kit/send! channel
                  (json/generate-string
                   {:type "error"
                    :error error-msg})))

(defn handle-client-message [channel message]
  (log/info "Handling client message:" message)
  (let [data (json/parse-string message true)]
    (try
      (case (or (:type data) (:action data))
        "get-initial-state"
        (let [user-id (:userId data)
              parties (state/filter-user-parties (:parties @state/app-state) user-id)]
          (log/info "Sending initial state to user" user-id "parties:" parties)
          (http-kit/send! channel
                          (json/generate-string
                           {:type "parties-update"
                            :parties parties})))

        "create-party"
        (let [user-id (:userId data)
              party-name (:name data)
              threshold (:threshold data)
              max-members (:maxMembers data)
              new-state (state/add-party party-name user-id threshold max-members)]
          (log/info "Created new party for user" user-id)
          (notify-clients (:parties @state/app-state)))

        "join-party"
        (let [user-id (:userId data)
              invite-code (:inviteCode data)
              party (state/get-party-by-invite-code invite-code)
              new-state (state/add-member-to-party (:id party) user-id)]
          (log/info "User" user-id "joined party" (:id party))
          (notify-clients (:parties @state/app-state)))

        "set-ready"
        (let [user-id (:userId data)
              party-id (UUID/fromString (:partyId data))
              _ (log/info "Setting ready status for user:" user-id "in party:" party-id)
              updated-state (state/set-user-ready party-id user-id)
              _ (log/info "State after setting ready:" updated-state)
              parties (:parties updated-state)]
          (log/info "User" user-id "set ready in party" party-id)
          (notify-clients (:parties @state/app-state)))

        "unset-ready"
        (let [user-id (:userId data)
              party-id (UUID/fromString (:partyId data))
              new-state (state/unset-user-ready party-id user-id)]
          (log/info "User" user-id "unset ready in party" party-id)
          (notify-clients (:parties @state/app-state)))

        "leave-party"
        (let [user-id (:userId data)
              party-id (UUID/fromString (:partyId data))
              new-state (state/remove-member-from-party party-id user-id)]
          (log/info "User" user-id "left party" party-id)
          (notify-clients (:parties @state/app-state)))

        "delete-party"
        (let [user-id (:userId data)
              party-id (UUID/fromString (:partyId data))
              old-state (state/delete-party party-id user-id)]
          (log/info "User" user-id "deleted party" party-id)
          (notify-clients (:parties @state/app-state)))

        ;; Default case
        (do
          (log/warn "Unknown message type/action:" (or (:type data) (:action data)))
          (send-error channel "Unknown action")))

      (catch Exception e
        (log/error "Error processing message:" (.getMessage e))
        (send-error channel (str "Error: " (.getMessage e)))))))

(defn request-handler [request]
  (log/info "New WebSocket connection request")
  (http-kit/as-channel request
                       {:on-open (fn [channel]
                                   (let [client-id (str (java.util.UUID/randomUUID))
                                         user-id (get-in request [:query-params "userId"])]
                                     (log/info "New WebSocket connection established for user:" user-id)
                                     (swap! clients assoc client-id {:channel channel :user-id user-id})))

                        :on-receive (fn [channel data]
                                      (handle-client-message channel data))

                        :on-close (fn [channel status]
                                    (let [client-id (some (fn [[k v]] (when (= (:channel v) channel) k)) @clients)]
                                      (log/info "WebSocket connection closed for client" client-id "with status" status)
                                      (swap! clients dissoc client-id)))}))

(defmethod state/notify-state-change :parties-update [_ parties]
  (notify-clients parties))
