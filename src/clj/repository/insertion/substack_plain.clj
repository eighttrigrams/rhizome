(ns repository.insertion.substack-plain
  (:require [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            utils
            [utils.url :as url]
            [scrapers.substack :as substack]
            upload))

(defn match? [title]
  (re-matches #"https://substack.com\/home\/post\/p-.*" title))

(defn save-article [db url context-ids-set]
  (prn "save article url" url)
    (let [url                  (url/url-without-query-params url)
          substack-platform-id (common/get-item-or-throw-error db "Substack")
          #_#_substacks-id         (common/get-item-or-throw-error db "Substacks")
          {:keys [title content date year image type]}
          ,,(substack/get-post url substack/extract-content)
          articles-id          (common/get-item-or-throw-error db "Articles")
          #_#_year-id              (common/get-item-or-throw-error db year)
          #_#_summary              (and should-capture-summary?
                                    (chatgpt/get-summary content))
          #_#_substack-id          (create-or-take-substack-id 
                                db 
                                (if external?
                                  (convert-external url)
                                  (convert url))
                                substack-platform-id
                                substacks-id)
          context-ids-set     (conj context-ids-set
                                    #_(or substack-id articles-id) ;; hack 
                                    articles-id 
                                    substack-platform-id
                                    #_year-id
                                    )
          existing-item       (datastore/get-item-by-path db 
                                                          "data->'resource-links'->>'substack-article'" 
                                                          url)]
      
      (prn title content date year image type existing-item)
      
      (if (:id existing-item)
        (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
        (let [item  (common/insert-item db 
                                        title 
                                        "" 
                                        context-ids-set 
                                        {:substack-article url})]
          (datastore/insert-date db (:id item) date)
          (log/info (str "created new item" item))
          (when image (try (upload/upload-preview-file db {:tempfile image} (:id item) "false")
                           (catch Exception e
                             (log/error (str "problem while trying to create preview image for substack article. message" (.getMessage e))))))))))