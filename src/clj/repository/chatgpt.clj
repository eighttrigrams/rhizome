(ns repository.chatgpt
  (:require [cambium.core :as log]
            [datastore.config :as config]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defn form-params [content]
  {:model    "gpt-3.5-turbo-0125",
   :messages [{:role    "system",
               :content "You are a professional summary writer who delivers executive summaries of essays to CEOs. Every input will be an essay. Please summarize in 2 paragraphs."}
              {:role    "user",
               :content content}]})

(defn get-summary [content]
  (when-let [token (:chat-gpt-token config/config)]
    (try
      (let [parsed-response-body
              (json/parse-string 
               (:body (http/post
                       "https://api.openai.com/v1/chat/completions"
                       {:headers      {"Authorization" (str "Bearer " token)}
                        :content-type :json
                        :form-params  (form-params content)}))
               true)]
        (:content (:message (first (:choices parsed-response-body)))))
      (catch Exception e
        (log/warn (str "something went wrong using chatgpt" (.getMessage e)))
        nil))))
