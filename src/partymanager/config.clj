(ns partymanager.config
  (:require
   [clj-http.client :as client]
   [clojure.tools.logging :as log]
   [environ.core :refer [env]]))

;; Configuration
(def telegram-token (env :telegram-token))
(def domain-url (env :domain-url))
(def port (or (some-> (env :port) Integer/parseInt) 3001))

;; Configuration validation
(defn validate-config! []
  (when (or (nil? telegram-token) (nil? domain-url))
    (log/error "Required environment variables are not set!")
    (System/exit 1))
  (when (< port 1024)
    (log/error "Port must be greater than 1024!")
    (System/exit 1)))

;; Webhook setup
(defn set-webhook []
  (try
    (let [response (client/post (str "https://api.telegram.org/bot" telegram-token "/setWebhook")
                                {:form-params {:url (str domain-url "/webhook")}
                                 :content-type :json})]
      (log/info "Webhook set:" (:body response)))
    (catch Exception e
      (log/error "Webhook setup error:" (.getMessage e)))))

;; WebApp menu button setup
(defn set-menu-button []
  (try
    (let [response (client/post (str "https://api.telegram.org/bot" telegram-token "/setChatMenuButton")
                                {:form-params {:menu_button {:type "web_app"
                                                             :text "Open App"
                                                             :web_app {:url (str domain-url)}}}
                                 :content-type :json})]
      (log/info "Menu button set:" (:body response)))
    (catch Exception e
      (log/error "Menu button setup error:" (.getMessage e)))))

;; Bot setup
(defn configure-bot! []
  (validate-config!)
  (set-webhook)
  (set-menu-button)
  (log/info "Bot configuration completed"))
