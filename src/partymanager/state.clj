(ns partymanager.state
  (:require
   [clojure.tools.logging :as log]
   [partymanager.storage :as storage]
   [partymanager.state :as state]
   [partymanager.message-handler :as message]
   [malli.core :as m]
   [malli.error :as me])
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

(def GroupMember-schema
  [:map
   [:id int?]
   [:ready boolean?]
   [:ready-at {:optional true} [:maybe int?]]
   [:first-name string?]
   [:username {:optional true} string?]
   [:language-code string?]
   [:registration-date int?]])

(def Group-schema
  [:map
   [:id uuid?]
   [:name string?]
   [:creator-id int?]
   [:invite-code string?]
   [:members [:vector GroupMember-schema]]
   [:threshold int?]
   [:max-members int?]
   [:created-at int?]
   [:last-ready-time int?]
   [:last-activity {:optional true} int?]])

(def AppState-schema
  [:map
   [:groups [:vector Group-schema]]
   [:users [:map-of int? User-schema]]])

;; Application state atom must be declared first
(def app-state (atom {:groups []
                      :users {}}))

;; Group timers atom
(def timers (atom {})) ;; {group-id -> {user-id -> future}}

;; Storage initialization
(defn init-storage! [initial-state]
  (if (m/validate AppState-schema initial-state)
    (reset! app-state (storage/init-storage! initial-state))
    (throw (ex-info "Invalid state format"
                    {:error (me/humanize (m/explain AppState-schema initial-state))}))))

;; State update
(defn swap-state! [f & args]
  (let [result (apply swap! app-state f args)
        saved? (storage/save-state! @app-state)]
    (when-not saved?
      (log/error "Failed to save application state"))
    result))

;; Code generation
(defn generate-code
  "Generate a new unique numeric invite code"
  []
  (let [existing-codes (set (map :invite-code (:groups @app-state)))
        new-code (format "%06d" (+ 100000 (rand-int 900000)))]
    (if (contains? existing-codes new-code)
      (recur) ; if code already exists - generate new one
      new-code)))

(def ready-timeout (* 1 60 1000)) ;; 15 minutes in milliseconds

(def UUID-schema [:uuid])

(defn get-group-by-id
  "Get group by id"
  [group-id]
  (when-not (m/validate UUID-schema group-id)
    (throw (ex-info "Invalid group id format"
                    {:status 400
                     :error "Group id must be a valid UUID"})))
  (let [group (first (filter #(= (:id %) group-id) (:groups @app-state)))]
    (when (nil? group)
      (throw (ex-info "Group not found"
                      {:status 404
                       :error "Group not found"})))
    group))

(defn get-group-by-invite-code
  "Get group by invite code"
  [invite-code]

  (let [group (first (filter #(= (:invite-code %) invite-code) (:groups @app-state)))]
    (when (nil? group)
      (throw (ex-info "Group not found"
                      {:status 404
                       :error "Group not found"})))
    group))
(defn create-group-member
  "Create a new member of the group by user data"
  [user-id data]

  {:id user-id
   :ready false
   :first-name (:first-name data)
   :username (:username data)
   :language-code (:language-code data)
   :registration-date (:registration-date data)})

(defn get-user
  "Get user data by id"
  [user-id]
  (log/info "Get user by id:" user-id "(type:" (type user-id) ")")
  (let [user-data (get-in @app-state [:users user-id])]
    (log/info "Found user data:" user-data)
    (when (nil? user-data)
      (throw (ex-info "User not found" {:status 404 :error "User not found"})))
    user-data))

(defn get-user-groups
  "Get groups where user is a member"
  [user-id]

  (filter #(some (fn [m] (= (:id m) user-id)) (:members %)) (:groups @app-state)))

(defn- group-full?
  "Check if group is full"
  [group]

  (>= (count (:members group)) (:max-members group)))

(defn- member-exists?
  "Check if user is already a member of the group"
  [group user-id]

  (some #(= (:id %) user-id) (:members group)))

(defn check-group-limit!
  "Check user's group participation limit"
  [user-id]
  (let [current-count (count (get-user-groups user-id))]
    (when (>= current-count 5)
      (throw (ex-info "Group limit reached"
                      {:status 422
                       :error (format "❌ Group participation limit (5) exceeded. Current count: %d" current-count)})))))

(defn add-member-to-group
  "Adds user to group, checking:
    - if member limit is not exceeded
    - if user is not already in group
  Returns updated state or throws exception"
  [group-id user-id]

  (check-group-limit! user-id)
  (swap-state! update-in [:groups]
               (fn [groups]
                 (mapv (fn [g]
                         (if (= (:id g) group-id)
                           (cond
                             (group-full? g)
                             (throw (ex-info "Group capacity exceeded" {:status 422
                                                                        :body (str "Group is full. Maximum members: " (:max-members g))}))

                             (member-exists? g user-id)
                             (throw (ex-info "Duplicate member" {:status 422
                                                                 :body (str "User is already a member of group " (:name g))}))

                             :else
                             (update g :members conj
                                     (create-group-member user-id (get-user user-id))))
                           g))
                       groups))))

(defn cancel-ready-timer
  "Cancel ready timer for the user in the group"
  [group-id user-id]

  (when-let [user-timer (get-in @timers [group-id user-id])]
    (future-cancel user-timer)
    (swap! timers update group-id dissoc user-id)))

(defn add-group
  "Add new group to the state"
  [group-name user-id threshold max-members]

  (check-group-limit! user-id)
  (let [uuid (UUID/randomUUID)
        group {:id uuid
               :name group-name
               :creator-id user-id
               :invite-code (generate-code)
               :members [(create-group-member user-id (state/get-user user-id))]
               :threshold (Integer/parseInt threshold)
               :max-members (Integer/parseInt max-members)
               :created-at (System/currentTimeMillis)
               :last-ready-time 0}]
    (swap-state! update :groups conj group)

    (log/info "Created group" group)
    group))

(defn delete-group
  "Delete group by id from the state"
  [group-id user-id]

  (let [group (get-group-by-id group-id)
        group-creator-id (:creator-id group)]

    (when (not= user-id group-creator-id)
      (throw (ex-info "Only creator allow to delete group"
                      {:status 422
                       :body (format "❌ Only the creator can delete the group")})))

    (swap-state! update :groups #(filterv (fn [g] (not= (:id g) group-id)) %))

    (log/info "Deleting group" group-id "creator-id" group-creator-id)
    group))

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

(defn unset-user-ready
  "Unset user ready status in the group"
  [group-id user-id]

  (cancel-ready-timer group-id user-id)
  (swap-state! update :groups
               (fn [groups]
                 (mapv #(if (= (:id %) group-id)
                          (update % :members
                                  (fn [members]
                                    (mapv (fn [member]
                                            (if (= (:id member) user-id)
                                              (assoc member
                                                     :ready false
                                                     :ready-at nil)
                                              member))
                                          members)))
                          %) groups))))
(defn remove-member-from-group
  "Remove user from the group in state"
  [group-id user-id]
  (let [group (get-group-by-id group-id)]
    (when group
      (cancel-ready-timer group-id user-id)
      (let [updated-group (swap-state! update :groups
                                       (fn [groups]
                                         (mapv (fn [g]
                                                 (if (= (:id g) group-id)
                                                   (update g :members
                                                           (fn [members]
                                                             (vec (remove #(= (:id %) user-id) members))))
                                                   g))
                                               groups)))]
        (when (empty? (:members (get-group-by-id group-id)))
          (delete-group group-id (:creator-id group)))

        (log/info "Removed member" user-id "from group" group-id)
        updated-group))))

(defn set-ready-timer [group-id user-id]
  (swap! timers assoc-in [group-id user-id]
         (future
           (Thread/sleep ready-timeout)
           (unset-user-ready group-id user-id))))

;; User readiness setup
(defn notify-group-ready
  "Sends notification to all group members when readiness threshold is reached"
  [group]
  (let [ready-count (count (filter :ready (:members group)))]
    (doseq [member (:members group)]
      (message/send-telegram-message
       (:id member)
       (format "🎉 Group \"%s\" is ready to start!\nMembers ready: %d/%d"
               (:name group)
               ready-count
               (:threshold group))))))

;; Helper functions for readiness management
(defn- update-member-ready-status
  "Updates member's readiness status"
  [member user-id now]
  (if (= (:id member) user-id)
    (assoc member
           :ready true
           :ready-at now)
    member))

(defn- update-group-ready-status
  "Updates group's readiness status"
  [group user-id now]
  (-> group
      (assoc :last-activity now)
      (update :members #(mapv (fn [m] (update-member-ready-status m user-id now)) %))))

(defn- check-and-notify-threshold
  "Checks if readiness threshold is reached and sends notification"
  [group]
  (let [ready-count (count (filter :ready (:members group)))]
    (when (= ready-count (:threshold group))
      (notify-group-ready group))))

(defn set-user-ready [group-id user-id]
  (log/info "Set user ready:" group-id user-id)
  (let [group (get-group-by-id group-id)
        user (get-user user-id)
        now (System/currentTimeMillis)]

    (set-ready-timer group-id user-id)

    (let [updated-state (swap-state! update :groups
                                     (fn [groups]
                                       (mapv #(if (= (:id %) group-id)
                                                (update-group-ready-status % user-id now)
                                                %)
                                             groups)))
          updated-group (first (filter #(= (:id %) group-id) (:groups updated-state)))]

      (check-and-notify-threshold updated-group)
      (storage/save-state! @app-state))))
