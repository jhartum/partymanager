(ns partymanager.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [org.httpkit.server :as http-kit]
   [partymanager.config :as config]
   [partymanager.message-handler :as message-handler]
   [partymanager.state :as state]
   [partymanager.websocket :as websocket]
   [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
   [ring.middleware.json :as middleware]))

;; Add near the top of the file, after the requires
(System/setProperty "clojure.tools.logging.factory" "clojure.tools.logging.impl/slf4j-factory")

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

;; Routing
(defroutes app-routes
  (GET "/" []
    (let [html (slurp (io/resource "public/index.html"))
          html-with-env (string/replace
                         html
                         #"</head>"
                         (str "<script>window.ENV = {API_BASE_URL: '" config/api-base-url "'};</script></head>"))]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body html-with-env}))

  (GET "favicon.ico" []
    {:status 204})

  (POST "/webhook" req
    (webhook-handler req))

  (GET "/ws" [] websocket/request-handler)

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

;; Add before the -main function
(defn configure-logging! []
  (.setLevel (java.util.logging.Logger/getLogger "")
             java.util.logging.Level/ALL))

;; Entry point
(defn -main []
  (configure-logging!)
  (config/configure-bot!)
  (state/init-storage! {:groups [] :users {}})
  (schedule-code-updates!)
  (http-kit/run-server app {:port config/port})
  (log/info "Server started on port" config/port))
