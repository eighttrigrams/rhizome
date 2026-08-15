(ns repository.insertion
  (:require [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.relations :as relations]
            [repository.insertion.substack :as substack]
            [repository.insertion.substack-note :as substack-note]
            [repository.insertion.twitter-tweet :as twitter-tweet]
            [repository.insertion.apple-pods :as apple-pods]
            [repository.insertion.substack-external :as substack-external]
            [repository.insertion.substack-plain :as substack-plain]
            [repository.insertion.youtube :as youtube]
            [repository.insertion.github :as github]
            #_[repository.insertion.file :as file]
            [repository.insertion.batch :as batch]
            [repository.insertion.simonwillison :as simonwillison]
            [repository.insertion.website :as website]))

(defn- normal-item-insertion
  [db title context-ids-set source]
  (datastore/new-item db title "" context-ids-set nil source))

(defn ensure-contexts!
  "Put the contexts the caller named onto `item`, and hand it back.

   Each ingester files what it makes under the contexts of the site it knows --
   Websites, Articles, the channel, the repo -- and those are right and stay.
   The contexts asked for here are a second thing, and they are equally right:
   whoever inserted the link said where they wanted it. So both go on.

   The case where they came apart is the link the graph already holds. An
   ingester answers that with the item it found rather than one it made, and
   that item stands under the contexts of the first time round. Nothing is
   stored twice -- that is the point of finding it -- but it is filed where this
   caller asked for it as well.

   A no-op where the insertion already did it, which is every fresh item, and on
   a batch import, which answers with a list rather than an item."
  [db item context-ids-set]
  (if-not (map? item)
    item
    (let [missing (remove (set (keys (get-in item [:data :contexts]))) context-ids-set)]
      (doseq [context-id missing]
        (when-let [context (datastore/get-item db {:id context-id})]
          ;; Re-read per link: link-item-to-another-item! rebuilds the whole
          ;; context map out of the item it is handed, so a stale one would take
          ;; back the link before it.
          (relations/link-item-to-another-item! db
                                                (datastore/get-item db {:id (:id item)})
                                                context
                                                true)))
      ;; Merged onto what the ingester answered, rather than returned bare:
      ;; :previously-existing-item? lives on that map and the callers branch on it.
      (if (seq missing) (merge item (datastore/get-item db {:id (:id item)})) item))))

(defn insert-item
  ([db title selected-item selected-secondary-contexts-set]
   (insert-item db title selected-item selected-secondary-contexts-set "app"))
  ([db title selected-item selected-secondary-contexts-set source]
   (log/info (str "Import for " title))
   (let [context-ids-set (into #{} (conj selected-secondary-contexts-set (:id selected-item)))
         item (cond
                (batch/match? title) (batch/ingest db title nil nil)
                (youtube/match? title) (youtube/ingest db title context-ids-set nil)
                (github/match? title) (github/save-article db title context-ids-set)
                (apple-pods/match? title) (apple-pods/ingest db title context-ids-set nil)
                (substack/match? title) ((substack/make:save-article false) db title context-ids-set)
                (substack-external/match? title)
                  (substack-external/save-article db title context-ids-set)
                (substack-plain/match? title) (substack-plain/save-article db title context-ids-set)
                (substack-note/match? title) (substack-note/ingest db title context-ids-set)
                (twitter-tweet/match? title) (twitter-tweet/ingest db title context-ids-set)
                (simonwillison/match? title) (simonwillison/ingest db title context-ids-set nil)
                (website/match? title) (website/ingest db title context-ids-set nil)
                :else (normal-item-insertion db title context-ids-set source))]
     (ensure-contexts! db item context-ids-set))))
