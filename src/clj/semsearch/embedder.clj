(ns semsearch.embedder
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(def ollama-url "http://127.0.0.1:11434")
(def ollama-model "nomic-embed-text")
(def embedding-dim 768)

(defn embed-text
  "Embed a string via Ollama. Returns a 768-dim vector of floats.
  Redef this var in tests to avoid hitting the network."
  [text]
  (-> (http/post (str ollama-url "/api/embeddings")
                 {:content-type :json
                  :body (json/generate-string {:model ollama-model :prompt text})
                  :as :json
                  :socket-timeout 10000
                  :connection-timeout 2000})
      :body
      :embedding))

(defn vec->pg-literal
  "Format a Clojure seq of numbers as a pgvector literal like '[1.0,2.0,...]'."
  [v]
  (str "[" (clojure.string/join "," v) "]"))
