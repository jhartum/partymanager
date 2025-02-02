(ns partymanager.storage
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string]
   [clojure.tools.logging :as log])
  (:import
   (java.util UUID)))

(def state-file "resources/state.json")

(defn- clean-key [k]
  (if (keyword? k)
    (last (clojure.string/split (str k) #":"))
    (str k)))

(defn- ensure-number [value]
  (try
    (if (string? value)
      (Long/parseLong (clean-key value))
      value)
    (catch Exception e
      (log/error "Failed to parse number from:" value)
      (throw e))))

(defn- prepare-state-for-save [state]
  (update state :users
          (fn [users]
            (reduce-kv
             (fn [m k v]
               (let [clean-key (ensure-number (clean-key k))]
                 (log/info "Saving user with key:" k "as" clean-key)
                 (assoc m clean-key v)))
             {}
             users))))

(defn save-state! [state]
  (try
    (log/info "Saving state to" state-file)
    (with-open [w (io/writer state-file)]
      (let [prepared-state (prepare-state-for-save state)]
        (log/info "Prepared state for save:" prepared-state)
        (json/generate-stream prepared-state w)))
    (log/info "State saved successfully")
    true
    (catch Exception e
      (log/error "Failed to save state:" (.getMessage e))
      false)))

(defn- transform-user [user]
  (-> user
      (update :chat-id ensure-number)
      (update :registration-date ensure-number)))

(defn- transform-group-member [member]
  (-> member
      (update :id ensure-number)
      (update :registration-date ensure-number)
      (update :ready-at #(when % (ensure-number %)))))

(defn- transform-group [group]
  (-> group
      (update :id #(if (string? %) (UUID/fromString %) %))
      (update :creator-id ensure-number)
      (update :threshold ensure-number)
      (update :max-members ensure-number)
      (update :created-at ensure-number)
      (update :last-ready-time ensure-number)
      (update :last-activity #(when % (ensure-number %)))
      (update :members #(mapv transform-group-member %))))

(defn- transform-users [users]
  (log/info "Transforming users map:" users)
  (let [result (reduce-kv
                (fn [m k v]
                  (try
                    (let [k-str (if (keyword? k) (name k) (str k))
                          numeric-key (Long/parseLong k-str)
                          transformed-user (transform-user v)]
                      (log/info "Transforming user key from" k "(type:" (type k) ") to" numeric-key "(type: Long)")
                      (log/info "Transformed user:" transformed-user)
                      (assoc m numeric-key transformed-user))
                    (catch Exception e
                      (log/error "Failed to transform user key:" k "error:" (.getMessage e))
                      m)))
                {}
                users)]
    (log/info "Users transformation result:" result)
    result))

(defn- transform-state [state]
  (if (map? state)
    (let [result (-> state
                     (update :groups #(mapv transform-group %))
                     (update :users transform-users))]
      (log/info "Transformed state:" result)
      result)
    state))

(defn load-state []
  (try
    (log/info "Loading state from" state-file)
    (when (.exists (io/file state-file))
      (with-open [r (io/reader state-file)]
        (let [state (json/parse-stream r true)
              _ (log/info "Loaded raw state:" state)
              transformed-state (transform-state state)]
          (log/info "State loaded and transformed successfully")
          transformed-state)))
    (catch Exception e
      (log/error "Failed to load state:" (.getMessage e))
      nil)))

(defn init-storage! [initial-state]
  (or (load-state) initial-state))
