(ns recording-mode
  (:require [cambium.core :as log]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

(defn log-and-guard
  "Log the intended write action, then either run `thunk` (when recording)
   or drop the request silently and return `dropped-response` instead.
   The log line does not reveal whether the request actually executed —
   it records the intent identically either way."
  [intent details dropped-response thunk]
  (log/info (assoc details :intent intent) (str "REST " intent))
  (if (enabled?) (thunk) dropped-response))
