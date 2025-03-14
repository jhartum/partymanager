(ns partymanager.config
  (:require
   [clj-http.client :as client]
   [clojure.tools.logging :as log]
   [environ.core :refer [env]]))

;; Configuration
(def telegram-token (env :telegram-token))
(def domain-url (env :domain-url))
(def port (or (some-> (env :port) Integer/parseInt) 3001))
(def api-base-url (or (env :api-url) "/web-app-api"))
(def webhook-base-url (or (env :webhook-url) "/webhook"))
(def menu-button-url (or (env :menu-button-url) "/"))

;; Configuration validation
(defn validate-config! []
  (log/info "Validating configuration...")
  (log/info "Telegram token:" telegram-token)
  (log/info "Domain url:" domain-url)
  (log/info "Port:" port)
  (log/info "Api url:" api-base-url)
  (log/info "Webhook url:" webhook-base-url)
  (log/info "Menu button url:" menu-button-url)

  (when (or (nil? telegram-token) (nil? domain-url))
    (log/error "Required environment variables are not set!")
    (System/exit 1))
  (when (< port 1024)
    (log/error "Port must be greater than 1024!")
    (System/exit 1)))

;; Webhook setup
(defn set-webhook []
  (try
    (let [url (str domain-url webhook-base-url)
          response (client/post (str "https://api.telegram.org/bot" telegram-token "/setWebhook")
                                {:form-params {:url url}
                                 :content-type :json})]
      (log/info "Webhook set:" (:body response) url)
      (log/info "Webhook response:" response))
    
    (catch Exception e
      (log/error "Webhook setup error:" (.getMessage e)))))

;; WebApp menu button setup
(defn set-menu-button []
  (try
    (let [url (str domain-url menu-button-url)
          response (client/post (str "https://api.telegram.org/bot" telegram-token "/setChatMenuButton")
                                {:form-params {:menu_button {:type "web_app"
                                                             :text "Open App"
                                                             :web_app {:url url}}}
                                 :content-type :json})]
      (log/info "Menu button set:" (:body response) url)
      (log/info "Menu button response:" response))
    (catch Exception e
      (log/error "Menu button setup error:" (.getMessage e)))))

;; Bot setup
(defn configure-bot! []
  (validate-config!)
  (set-webhook)
  (set-menu-button)
  (log/info "Bot configuration completed"))
