(ns semsearch.embedder
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [config :as config]))

(defn- configuration [] (:semsearch config/config))

(def embedding-dim 768)

(defn embed-text
  "Embed a string via Ollama. Returns a 768-dim vector of floats.
   Redef this var in tests to avoid hitting the network.

   On failure, throws an ex-info whose ex-data has :transient? — true for
   service-unreachable (ConnectException, SocketTimeoutException, ...) and
   false for HTTP non-2xx (Ollama replied but rejected this input). Callers
   use the flag to decide whether to mark the item as 'skipped for this
   description version' (genuine) or leave it for retry next run (transient)."
  [text]
  (let [{:keys [ollama-url ollama-model]} (configuration)]
    (try
      (-> (http/post (str ollama-url "/api/embeddings")
                     {:content-type :json
                      :body (json/generate-string {:model ollama-model :prompt text})
                      :as :json
                      :socket-timeout 10000
                      :connection-timeout 2000})
          :body
          :embedding)
      (catch clojure.lang.ExceptionInfo e
        (let [status (:status (ex-data e))]
          (throw (ex-info (str "embed-text failed"
                               (when status (str " (HTTP " status ")")))
                          {:transient? (nil? status)
                           :status status
                           :body (:body (ex-data e))}
                          e))))
      (catch Exception e
        (throw (ex-info (str "embed-text failed: " (.getMessage e))
                        {:transient? true}
                        e))))))

(defn vec->json
  "Format a Clojure seq of numbers as a JSON array string. sqlite-vec
   accepts this directly as an embedding value."
  [v]
  (json/generate-string v))
