(ns partymanager.timers
  (:import (java.util Timer TimerTask)))

(def timers (atom {})) ;; {group-id -> {user-id -> future}}

(defn set-timer
  "Sets a timer for user readiness status in a party.
   After 30 minutes, the ready status will be automatically reset."
  [party-id user-id reset-fn]
  (let [timer-key [party-id user-id]
        timer-duration (* 1 60 1000) ; 1 minutes in milliseconds
        timer-task (proxy [TimerTask] []
                     (run []
                       (reset-fn party-id user-id)))]
    (when-let [existing-timer (get @timers timer-key)]
      (.cancel existing-timer))
    (let [new-timer (doto (Timer.)
                      (.schedule timer-task timer-duration))]
      (swap! timers assoc timer-key new-timer))))

(defn cancel-timer
  "Cancels the timer for the specified party and user"
  [party-id user-id]
  (when-let [timer (get @timers [party-id user-id])]
    (.cancel timer)
    (swap! timers dissoc [party-id user-id])))

(defn clear-party-timers
  "Cancel all timers for the given party"
  [party-id]
  (doseq [[timer-key _] @timers
          :when (= (first timer-key) party-id)]
    (cancel-timer (first timer-key) (second timer-key))))
