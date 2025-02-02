(ns partymanager.api-handler
  (:require
   [clojure.tools.logging :as log]
   [partymanager.state :as state])
  (:import
   (java.util UUID)))

(defn- handle-create-group [body]
  (try
    (let [user-id (:userId body)
          group-name (:name body)
          threshold (:threshold body)
          max-members (:maxMembers body)

          new-state (state/add-group group-name user-id threshold max-members)
          response {:status 200 :body {:group new-state}}]
      (log/info "Create group request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing create-group request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-get-groups [body]
  (try
    (let [user-id (:userId body)

          groups (state/get-user-groups user-id)
          response {:status 200 :body {:groups groups}}]
      (log/info "Get groups request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing get-groups request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-join-group [body]
  (try
    (let [user-id (:userId body)
          invite-code (:inviteCode body)

          old-state (state/get-group-by-invite-code invite-code)
          new-state (state/add-member-to-group (:id old-state) user-id)
          response {:status 200 :body {:group new-state}}]
      (log/info "Join group request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing join-group request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-set-ready [body]
  (try
    (let [user-id (:userId body)
          group-id (UUID/fromString (:groupId body))

          new-state (state/set-user-ready group-id user-id)
          response {:status 200 :body {:group new-state}}]
      (log/info "Set ready request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing unset-ready request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-unset-ready [body]
  (try
    (let [user-id (:userId body)
          group-id (UUID/fromString (:groupId body))
          new-state (state/unset-user-ready group-id user-id)
          response {:status 200 :body {:group new-state}}]
      (log/info "Unset ready request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing unset-ready request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-leave-group [body]
  (try
    (let [user-id (:userId body)
          group-id (UUID/fromString (:groupId body))

          new-state (state/remove-member-from-group group-id user-id)
          response {:status 200 :body {:group new-state}}]
      response)
    (catch Exception e
      (log/error "Error processing leave-group request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn- handle-delete-group [body]
  (try
    (let [user-id (:userId body)
          group-id (UUID/fromString (:groupId body))

          old-state (state/delete-group group-id user-id)
          response {:status 200 :body {:group old-state}}]
      (log/info "Delete group request response: " response)
      response)
    (catch Exception e
      (log/error "Error processing delete-group request:" e)
      {:status 500 :body {:error "Internal server error"}})))

(defn handle-api-request [req]
  (log/info "Web api request:" req)
  (try
    (let [body (:body req)
          action (:action body)]

      (case action
        "create-group" (handle-create-group body)
        "get-groups" (handle-get-groups body)
        "join-group" (handle-join-group body)
        "set-ready" (handle-set-ready body)
        "unset-ready" (handle-unset-ready body)
        "leave-group" (handle-leave-group body)
        "delete-group" (handle-delete-group body)
        {:status 400 :body {:error "Invalid action"}}))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (log/error "Error processing request:" e)
        {:status (or (:status data) 500)
         :body {:error (or (:error data) "Internal Server Error")}}))))
