(ns utils
  (:require [repository.chatgpt :as chatgpt]))

(defn condx [p & pairs] (first (keep (fn [[v f]] (when (p v) f)) (partition 2 pairs))))

(defn wrap-summary
  [summary]
  (str "--- ChatGPT | "
       (:model (chatgpt/configuration))
       " | BEGIN ---\n\n"
       summary
       "\n\n--- ChatGPT | "
       (:model (chatgpt/configuration))
       " | END ---"))
