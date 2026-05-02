(ns semsearch.embedder
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn ollama-url []
  (or (System/getenv "OLLAMA_URL") "http://127.0.0.1:11434"))

(def ollama-model (or (System/getenv "OLLAMA_EMBED_MODEL") "nomic-embed-text"))

(def embedding-dim 768)

(defn embed-text
  "Embed a string via Ollama. Returns a 768-dim vector of floats.
   Redef this var in tests to avoid hitting the network."
  [text]
  (-> (http/post (str (ollama-url) "/api/embeddings")
                 {:content-type :json
                  :body (json/generate-string {:model ollama-model :prompt text})
                  :as :json
                  :socket-timeout 10000
                  :connection-timeout 2000})
      :body
      :embedding))

(defn vec->json
  "Format a Clojure seq of numbers as a JSON array string. sqlite-vec
   accepts this directly as an embedding value."
  [v]
  (json/generate-string v))
