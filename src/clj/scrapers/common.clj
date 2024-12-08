(ns scrapers.common
  (:require [clojure.string :as str]
            [hickory.select :as select]
            [clj-http.client :as http]
            [hickory.core :as html]))

(defn get-property [tree name]
   (-> (select/select (select/attr "property" (fn [x] (= x name))) tree)
       first
       :attrs
       :content
       str/trim))

(defn extract-text [content]
  (str/join (doall (reduce (fn [acc val]
                             (cond (string? val)
                                   (concat acc [val])
                                   (and (= :element (:type val)) 
                                        (:content val))
                                   (concat acc (extract-text (:content val)))
                                   :else acc))
                           [] 
                           content))))

(defn get-post [url extract-content]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content 
                      (first 
                       (select/select
                        (select/tag "title")
                        tree))))]
    [title (-> tree 
               extract-content  
               extract-text)]))
