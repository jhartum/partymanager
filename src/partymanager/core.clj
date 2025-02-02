(ns partymanager.core
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [partymanager.api-handler :as api-handler]
   [partymanager.config :as config]
   [partymanager.message-handler :as message-handler]
   [partymanager.state :as state]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
   [ring.middleware.json :as middleware])
  (:gen-class))

;; Code update scheduler
(defn schedule-code-updates! []
  (future
    (try
      (loop []
        (Thread/sleep (* 10 60 1000))
        (state/swap-state! update :groups
                           (fn [groups]
                             (mapv #(assoc % :invite-code (state/generate-code)) groups)))
        (recur))
      (catch Exception e
        (log/error "Code update error:" (.getMessage e))))))

(defn webhook-handler [req]
  (log/info "Webhook received:" req)
  (let [chat-id (get-in req [:body :message :chat :id])
        user-id (get-in req [:body :message :from :id])
        user-data (get-in req [:body :message :from])]

    (state/save-user user-id chat-id user-data)
    (message-handler/handle-message (get-in req [:body :message])))

  {:status 200 :body "OK"})

;; Base path configuration
(def base-path "/partymanager")

;; Routing
(defroutes app-routes
  (GET (str base-path "/") []
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (io/input-stream (io/resource "public/index.html"))})

  (GET (str base-path "/favicon.ico") []
    {:status 204})

  (POST (str base-path "/webhook") req
    (webhook-handler req))

  (POST (str base-path "/web-app-api") req
    (api-handler/handle-api-request req))

  ;; Serve static files from resources/public
  ;; (route/resources (str base-path "/") {:root "public"})

  ;; Handle unmatched routes
  (route/not-found "Not Found"))

;; Middleware
(def app
  (-> app-routes
      (middleware/wrap-json-body {:keywords? true :bigdecimals? true})
      (middleware/wrap-json-response)
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:responses :content-types] true)
                         (assoc-in [:static :resources] false)))))

;; Entry point
(defn -main []
  (config/configure-bot!)
  (state/init-storage! {:groups [] :users {}})
  (schedule-code-updates!)
  (jetty/run-jetty app {:port config/port :join? false})
  (log/info "Server started on port" config/port))
