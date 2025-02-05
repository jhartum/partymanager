(ns partymanager.schema
  (:require [malli.core :as m]))

(def User
  [:map
   [:id int?]
   [:chat-id int?]
   [:first-name string?]
   [:username {:optional true} string?]
   [:language-code string?]])

(def PartyMember
  [:map
   [:id int?]
   [:chat-id int?]
   [:first-name string?]
   [:username {:optional true} string?]
   [:ready boolean?]
   [:ready-at {:optional true} inst?]])

(def Party
  [:map
   [:id uuid?]
   [:name string?]
   [:creator-id int?]
   [:invite-code string?]
   [:members [:vector PartyMember]]
   [:threshold int?]
   [:max-members int?]
   [:created-at inst?]
   [:last-activity {:optional true} inst?]])

(def AppState-schema
  [:map
   [:parties [:vector any?]]
   [:users   [:map-of int? any?]]])

(def CreatePartyCommand
  [:map
   [:name string?]
   [:creator-id int?]
   [:threshold int?]
   [:max-members int?]])

(def JoinPartyCommand
  [:map
   [:invite-code string?]
   [:user-id int?]])

(def LeavePartyCommand
  [:map
   [:party-id uuid?]
   [:user-id int?]])

(def SetReadyCommand
  [:map
   [:party-id uuid?]
   [:user-id int?]
   [:ready boolean?]])

(defn transform-state [raw-state]
  (update raw-state :users
    (fn [users]
      (into {}
        (map (fn [[k v]]
               [(if (keyword? k)
                  (try
                    (Integer/parseInt (name k))
                    (catch Exception _ k))
                  k)
                v])
             users))))) 