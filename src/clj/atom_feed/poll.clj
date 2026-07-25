(ns atom-feed.poll
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [scrapers.atom-feed :as feed]
            [youtube.poll :as youtube-poll]
            [repository.insertion :as insertion])
  (:import [java.util.concurrent Executors TimeUnit]))

(def ^:private poll-interval-minutes 5)

(defn list-feeds
  [db]
  (->> (jdbc/execute! db
                      (sql/format {:select [:id :feed_url :name]
                                   :from [:atom_poll_feeds]
                                   :order-by [[:added_at :desc]]}))
       (mapv (fn [row]
               {:id (:atom_poll_feeds/id row)
                :feed-url (:atom_poll_feeds/feed_url row)
                :name (:atom_poll_feeds/name row)}))))

(defn- feed-exists?
  [db feed-url]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [:id]
                                            :from [:atom_poll_feeds]
                                            :where [:= :feed_url [:inline feed-url]]})))))

(defn add-feed!
  [db input]
  (let [feed-url (str/trim (or input ""))]
    (when (re-matches #"https?://\S+" feed-url)
      (when-not (feed-exists? db feed-url)
        (let [title (try (:title (feed/fetch-feed feed-url)) (catch Exception _ nil))]
          (jdbc/execute-one! db
                             (sql/format {:insert-into [:atom_poll_feeds]
                                          :columns [:feed_url :name]
                                          :values [[[:inline feed-url] [:inline title]]]})))))))

(defn delete-feed!
  [db id]
  (jdbc/execute-one! db
                     (sql/format {:delete-from [:atom_poll_feeds]
                                  :where [:= :id [:inline id]]})))

(defn- seen?
  [db entry-id]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [:entry_id]
                                            :from [:atom_poll_seen]
                                            :where [:= :entry_id [:inline entry-id]]})))))

(defn- mark-seen!
  [db entry-id]
  (jdbc/execute-one! db
                     (sql/format {:insert-into [:atom_poll_seen]
                                  :columns [:entry_id]
                                  :values [[[:inline entry-id]]]})))

(defn- fill-description!
  [db item summary]
  (when (and (map? item)
             (:id item)
             (not (:previously-existing-item? item))
             (str/blank? (:description item))
             (seq summary))
    (datastore/update-context-description db {:id (:id item) :description summary} "scraper")))

(defn poll-once!
  [db]
  (let [imports-id (youtube-poll/ensure-imports-context! db)]
    (doseq [{:keys [feed-url name]} (list-feeds db)]
      (try
        (doseq [{:keys [entry-id link summary]} (:entries (feed/fetch-feed feed-url))]
          (try
            (when (and link (not (seen? db entry-id)))
              (log/info (str "atom-poll: importing " link " from " (or name feed-url)))
              (let [item (insertion/insert-item db link {:id imports-id} nil "scraper")]
                (fill-description! db item summary))
              (mark-seen! db entry-id))
            (catch Exception e
              (log/error e (str "atom-poll: failed importing " link)))))
        (catch Exception e
          (log/error e (str "atom-poll: failed polling feed " feed-url)))))))

(defonce ^:private scheduler (atom nil))

(defn start-scheduler!
  [db]
  (when-not @scheduler
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleAtFixedRate
        exec
        ^Runnable (fn []
                    (try (poll-once! db)
                         (catch Throwable e (log/error e "atom-poll: tick failed"))))
        (long 30)
        (long (* 60 poll-interval-minutes))
        TimeUnit/SECONDS)
      (reset! scheduler exec)
      (log/info (str "atom-poll: scheduler started (every " poll-interval-minutes " min)")))))

(defn stop-scheduler!
  []
  (when-let [exec @scheduler]
    (.shutdownNow exec)
    (reset! scheduler nil)))

(defn list-atom-poll-feeds
  [{:keys [db]}]
  (fn [_state] {:atom-poll-feeds (list-feeds db)}))

(defn add-atom-poll-feed
  [{:keys [db]}]
  (fn [_state input]
    (add-feed! db input)
    {:atom-poll-feeds (list-feeds db)}))

(defn delete-atom-poll-feed
  [{:keys [db]}]
  (fn [_state id]
    (delete-feed! db id)
    {:atom-poll-feeds (list-feeds db)}))
