(ns repository.chatgpt
  (:require [cambium.core :as log]
            [datastore.config :as config]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defn configuration [] (:chat-gpt config/config))

(defn form-params [content]
  {:model    (:model (configuration)),
   :messages [{:role    "system",
               :content (:prompt (configuration))}
              {:role    "user",
               :content content}]})

(defn get-summary [content]
  (log/info (str "Using ChatGPT configuration: " (configuration)))
  (when-let [token (:token (configuration))]
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
