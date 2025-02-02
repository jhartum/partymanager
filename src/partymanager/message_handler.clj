(ns partymanager.message-handler
  (:require
   [clj-http.client :as client]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [partymanager.config :refer [telegram-token domain-url]]))

(defn send-telegram-message
  "Sends a message via Telegram Bot API"
  [chat-id text & [reply-markup]]
  (let [url (str "https://api.telegram.org/bot" telegram-token "/sendMessage")
        params (cond-> {:chat_id chat-id
                       :text text
                       :parse_mode "Markdown"}
                reply-markup (assoc :reply_markup (json/generate-string reply-markup)))]
    (try
      (log/info "Sending message to Telegram API:"
                "\nURL:" url
                "\nParameters:" (json/encode params))
      (let [response (client/post url
                                 {:form-params params
                                  :content-type :json
                                  :as :json
                                  :throw-exceptions false})]
        (when (not= 200 (:status response))
          (log/error "Telegram API returned error:"
                     "\nStatus:" (:status response)
                     "\nBody:" (:body response))))
      (catch Exception e
        (if-let [response (ex-data e)]
          (log/error "Message sending error:"
                     "\nStatus:" (:status response)
                     "\nBody:" (-> response :body json/decode)
                     "\nRequest params:" (json/encode params))
          (log/error "Unexpected error:" (.getMessage e)))))))

(defn handle-message [message]
  (let [chat-id (get-in message [:chat :id])]
      ;; Sending response via WebApp
    (send-telegram-message
     chat-id
     (str "🌟 Welcome!\nUse the button to manage groups:")
     {:inline_keyboard [[{:text "📲 Open App"
                          :web_app {:url (str domain-url "/index.html")}}]]})))
