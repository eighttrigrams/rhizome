(ns utils.url
  (:require [clojure.string :as str]
            [ring.util.codec :refer [url-encode form-decode]]
            [clojure.java.io :as io]))

(defn parse-query-params
  [url-string]
  (let [uri (io/as-url url-string)
        query-string (.getQuery uri)
        query-params (form-decode query-string)]
    query-params))

(defn url-without-query-params [url] (first (str/split url #"\?")))

(defn make-query-string
  [m]
  (->> (for [[k v] m] (str (url-encode k) "=" (url-encode v)))
       (interpose "&")
       (apply str)))

(defn pick-query-params
  [url keys]
  (if (not (str/includes? url "?"))
    url
    (let [query-params (parse-query-params url)
          url-without-query-params (url-without-query-params url)
          query-params (select-keys query-params keys)]
      (str url-without-query-params
           (when (seq query-params) (str "?" (make-query-string query-params)))))))

(comment
  (pick-query-params "https://youtube.com/shorts/abc?m=13" []))

(defn get-subdomain
  [url-string]
  (let [url (java.net.URL. url-string)
        host (.getHost url)
        parts (str/split host #"\.")
        domain-parts-count 2
        subdomain (if (> (count parts) domain-parts-count)
                    (str/join "." (take (- (count parts) domain-parts-count) parts))
                    nil)]
    subdomain))
