(ns datastore.helpers
  (:require [cheshire.core :as json]))

(defn namespace-keys
  [ns-str m]
  (into {} (map (fn [[k v]]
                  [(keyword (str ns-str "/" (name k))) v]) m)))

(defn un-namespace-keys
  [m]
  (into {}
        (map (fn [[k v]]
               [(keyword (name k)) v])
             m)))

(defn simplify-date [m]
  (update m :date 
          #(when % 
             (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") %))))
