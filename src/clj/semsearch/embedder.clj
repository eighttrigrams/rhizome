(ns semsearch.embedder
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [config :as config]))

(defn- configuration [] (:semsearch config/config))

(def embedding-dim 768)

(defn embed-text
  "Embed a string via Ollama. Returns a 768-dim vector of floats.
   Redef this var in tests to avoid hitting the network."
  [text]
  (let [{:keys [ollama-url ollama-model]} (configuration)]
    (-> (http/post (str ollama-url "/api/embeddings")
                   {:content-type :json
                    :body (json/generate-string {:model ollama-model :prompt text})
                    :as :json
                    :socket-timeout 10000
                    :connection-timeout 2000})
        :body
        :embedding)))

(defn vec->json
  "Format a Clojure seq of numbers as a JSON array string. sqlite-vec
   accepts this directly as an embedding value."
  [v]
  (json/generate-string v))
