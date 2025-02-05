(ns partymanager.state
  (:require
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [partymanager.message-handler :as message]
   [partymanager.storage :as storage]
   [partymanager.timers :as timers]
   [partymanager.schema :as schema])
  (:import
   (java.util UUID)))

;; Data validation schemas
(def User-schema
  [:map
   [:chat-id int?]
   [:first-name string?]
   [:username {:optional true} string?]
   [:language-code string?]
   [:registration-date int?]])

(def PartyMember-schema
  [:map
   [:id int?]
   [:ready boolean?]
   [:ready-at {:optional true} [:maybe int?]]
   [:first-name string?]
   [:username {:optional true} string?]
   [:language-code string?]
   [:registration-date int?]])

(def Party-schema
  [:map
   [:id uuid?]
   [:name string?]
   [:creator-id int?]
   [:invite-code string?]
   [:members [:vector PartyMember-schema]]
   [:threshold int?]
   [:max-members int?]
   [:created-at int?]
   [:last-ready-time int?]
   [:last-activity {:optional true} int?]])

(def AppState-schema
  [:map
   [:parties [:vector Party-schema]]
   [:users [:map-of int? User-schema]]])

;; Application state atom must be declared first
(def app-state (atom {:parties []
                      :users {}}))

;; Storage initialization
(defn init-storage! [initial-state]
  (let [default-state {:parties []
                       :users {}}
        state-to-init (or initial-state default-state)]
    (if (m/validate AppState-schema state-to-init)
      (reset! app-state (storage/init-storage! state-to-init))
      (do
        (log/warn "Invalid initial state, using default empty state")
        (reset! app-state (storage/init-storage! default-state)))))
  @app-state)

;; Удалим импорт websocket и добавим мультиметод для уведомлений
(defmulti notify-state-change (fn [type data] type))
(defmethod notify-state-change :default [_ _] nil)

;; State update
(defn swap-state! [f & args]
  (let [result (apply swap! app-state f args)
        saved? (storage/save-state! @app-state)]
    (when-not saved?
      (log/error "Failed to save application state"))
    (notify-state-change :parties-update (:parties result))
    result))

;; Code generation
(defn generate-code
  "Generate a new unique numeric invite code"
  []
  (let [existing-codes (set (map :invite-code (:parties @app-state)))
        new-code (format "%06d" (+ 100000 (rand-int 900000)))]
    (if (contains? existing-codes new-code)
      (recur)
      new-code)))

(def UUID-schema [:uuid])

(defn save-user
  "Save user data to the state"
  [chat-id user-id user-data]
  (log/info "Saving user with id:" user-id "(type:" (type user-id) ")")
  (swap-state! assoc-in [:users user-id]
               {:chat-id chat-id
                :first-name (:first_name user-data)
                :username (:username user-data)
                :language-code (:language_code user-data)
                :registration-date (System/currentTimeMillis)}))

(defn get-user
  "Get user data by id"
  [user-id]
  (log/info "Get user by id:" user-id "(type:" (type user-id) ")")
  (let [user-data (get-in @app-state [:users user-id])]
    (log/info "Found user data:" user-data)
    (when (nil? user-data)
      (throw (ex-info "User not found" {:status 404 :error "User not found"})))
    user-data))

(defn- member-exists?
  "Check if user is already a member of the party"
  [party user-id]

  (some #(= (:id %) user-id) (:members party)))

(defn- update-member-ready-status
  "Updates member's readiness status"
  [member user-id now]
  (if (= (:id member) user-id)
    (assoc member
           :ready true
           :ready-at now)
    member))

(defn- update-party-ready-status
  "Updates party's readiness status"
  [party user-id now]
  (-> party
      (assoc :last-activity now)
      (update :members #(mapv (fn [m] (update-member-ready-status m user-id now)) %))))

;; User readiness setup
(defn notify-party-ready
  "Sends notification to all party members when readiness threshold is reached"
  [party]
  (let [ready-count (count (filter :ready (:members party)))]
    (when (= ready-count (:threshold party))  ; Отправляем сообщение только при достижении порога
      (doseq [member (:members party)]
        (message/send-telegram-message
         (:id member)
         (format "🎉 Пати \"%s\" готова к началу!\nУчастников готово: %d/%d"
                 (:name party)
                 ready-count
                 (:threshold party)))))))

(defn- check-and-notify-threshold
  "Checks if readiness threshold is reached and sends notification"
  [party]
  (let [ready-count (count (filter :ready (:members party)))]
    (log/info "Checking threshold for party:" (:name party)
              "Ready count:" ready-count
              "Threshold:" (:threshold party))
    (when (= ready-count (:threshold party))
      (log/info "Threshold reached! Sending notifications...")
      (notify-party-ready party))))

(defn- update-member-unready-status
  "Updates member's readiness status to not ready"
  [member user-id]
  (if (= (:id member) user-id)
    (assoc member
           :ready false
           :ready-at nil)
    member))

(defn unset-user-ready
  "Sets user's ready status to false in the party"
  [party-id user-id]
  (log/info "Unsetting user ready:" party-id user-id)
  (let [user-id-long (if (string? user-id)
                       (Long/parseLong user-id)
                       user-id)
        updated-state (swap-state! update :parties
                                   (fn [groups]
                                     (mapv (fn [group]
                                             (if (= (:id group) party-id)
                                               (-> group
                                                   (assoc :last-activity (System/currentTimeMillis))
                                                   (update :members
                                                           (fn [members]
                                                             (mapv (fn [member]
                                                                     (update-member-unready-status member user-id-long))
                                                                   members))))
                                               group))
                                           groups)))]
    (log/info "Updated state after unsetting ready:" updated-state)
    updated-state))

(defn set-user-ready
  "Sets user's ready status in the party"
  [party-id user-id]
  (log/info "Setting user ready:" party-id user-id)
  (let [user-id-long (if (string? user-id)
                       (Long/parseLong user-id)
                       user-id)
        updated-state (swap-state! update :parties
                                   (fn [groups]
                                     (mapv #(if (= (:id %) party-id)
                                              (let [updated-group (update-party-ready-status % user-id-long (System/currentTimeMillis))]
                                                (check-and-notify-threshold updated-group)
                                                updated-group)
                                              %)
                                           groups)))]
    (timers/set-timer party-id user-id-long unset-user-ready)
    (log/info "Updated state after setting ready:" updated-state)
    updated-state))

(defn get-party-by-id
  "Get party by id"
  [party-id]
  (when-not (m/validate UUID-schema party-id)
    (throw (ex-info "Invalid party id format"
                    {:status 400
                     :error "Party id must be a valid UUID"})))
  (let [party (first (filter #(= (:id %) party-id) (:parties @app-state)))]
    (when (nil? party)
      (throw (ex-info "Party not found"
                      {:status 404
                       :error "Party not found"})))
    party))

(defn get-party-by-invite-code
  "Get party by invite code"
  [invite-code]
  (log/info "Searching for party with invite code:" invite-code)
  (let [parties (:parties @app-state)
        _ (log/info "Current parties in state:" parties)
        party (first (filter #(= (:invite-code %) invite-code) parties))]
    (if party
      (do
        (log/info "Found party:" party)
        party)
      (do
        (log/error "No party found with invite code:" invite-code)
        (throw (ex-info "Party not found"
                        {:status 404
                         :error "Party not found"}))))))

(defn create-party-member
  "Create a new member of the party by user data"
  [user-id data]
  {:id user-id
   :ready false
   :first-name (:first-name data)
   :username (:username data)
   :language-code (:language-code data)
   :registration-date (:registration-date data)})

(defn get-user-parties
  "Get parties where user is a member"
  [user-id]
  (filter #(some (fn [m] (= (:id m) user-id)) (:members %)) (:parties @app-state)))

(defn- party-full?
  "Check if party is full"
  [party]
  (>= (count (:members party)) (:max-members party)))

(defn check-party-limit!
  "Check user's party participation limit"
  [user-id]
  (let [current-count (count (get-user-parties user-id))]
    (when (>= current-count 5)
      (throw (ex-info "Party limit reached"
                      {:status 422
                       :error (format "❌ Party participation limit (5) exceeded. Current count: %d" current-count)})))))

(defn add-member-to-party
  "Adds user to party, checking:
    - if member limit is not exceeded
    - if user is not already in party
  Returns updated state or throws exception"
  [party-id user-id]
  (log/info "Adding member" user-id "to party" party-id)
  (check-party-limit! user-id)
  (swap-state! update-in [:parties]
               (fn [parties]
                 (mapv (fn [p]
                         (if (= (:id p) party-id)
                           (do
                             (log/info "Processing party:" p)
                             (cond
                               (party-full? p)
                               (do
                                 (log/error "Party is full. Current members:" (count (:members p)))
                                 (throw (ex-info "Party capacity exceeded"
                                                 {:status 422
                                                  :error (str "Party is full. Maximum members: " (:max-members p))})))

                               (member-exists? p user-id)
                               (do
                                 (log/error "User" user-id "is already a member of party" (:name p))
                                 (throw (ex-info "Duplicate member"
                                                 {:status 422
                                                  :error (str "User is already a member of party " (:name p))})))

                               :else
                               (do
                                 (log/info "Adding new member to party")
                                 (update p :members conj
                                         (create-party-member user-id (get-user user-id))))))
                           p))
                       parties))))

(defn add-party
  "Add new party to the state"
  [party-name user-id threshold max-members]
  (check-party-limit! user-id)
  (let [uuid (UUID/randomUUID)
        party {:id uuid
               :name party-name
               :creator-id user-id
               :invite-code (generate-code)
               :members [(create-party-member user-id (get-user user-id))]
               :threshold (Integer/parseInt threshold)
               :max-members (Integer/parseInt max-members)
               :created-at (System/currentTimeMillis)
               :last-ready-time 0}]
    (swap-state! update :parties conj party)
    (log/info "Created party" party)
    party))

(defn delete-party
  "Delete party by id from the state"
  [party-id user-id]
  (let [party (get-party-by-id party-id)
        party-creator-id (:creator-id party)]
    (when (not= user-id party-creator-id)
      (throw (ex-info "Only creator allowed to delete party"
                      {:status 422
                       :body (format "❌ Only the creator can delete the party")})))
    (timers/clear-party-timers party-id)
    (swap-state! update :parties #(filterv (fn [p] (not= (:id p) party-id)) %))
    (log/info "Deleting party" party-id "creator-id" party-creator-id)
    party))

(defn remove-member-from-party
  "Remove user from the party in state"
  [party-id user-id]
  (let [party (get-party-by-id party-id)]
    (when party
      (timers/cancel-timer party-id user-id)
      (let [updated-party (swap-state! update :parties
                                       (fn [parties]
                                         (mapv (fn [p]
                                                 (if (= (:id p) party-id)
                                                   (update p :members
                                                           (fn [members]
                                                             (vec (remove #(= (:id %) user-id) members))))
                                                   p))
                                               parties)))]
        (when (empty? (:members (get-party-by-id party-id)))
          (delete-party party-id (:creator-id party)))

        (log/info "Removed member" user-id "from party" party-id)
        updated-party))))

(defn filter-user-parties
  "Returns parties that user is member of"
  [parties user-id]
  (log/info "Filtering parties for user:" user-id "from parties:" parties)
  (let [user-id-long (if (string? user-id)
                       (Long/parseLong user-id)
                       user-id)
        filtered (filter (fn [party]
                           (some (fn [member]
                                   (= (:id member) user-id-long))
                                 (:members party)))
                         parties)]
    (log/info "Filtered parties result:" filtered)
    filtered))

