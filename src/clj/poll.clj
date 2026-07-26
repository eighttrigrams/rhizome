(ns poll
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [scrapers.youtube-feed :as youtube-feed]
            [scrapers.atom-feed :as atom-feed]
            [repository.insertion :as insertion]
            [repository.insertion.youtube :as youtube-insertion])
  (:import [java.util.concurrent Executors TimeUnit]))

(def ^:private initial-delay-seconds 30)
(def ^:private interval-minutes 5)

(defn- imports-by-title
  [db]
  (try (:id (datastore/get-item-by-title db {:title "Imports"})) (catch Exception _ nil)))

(defn ensure-imports-context!
  [db]
  (or (:id (first (search/find-items-by-ids db {:human-readable-ids ["imports"]})))
      (let [id (or (imports-by-title db)
                   (:id (datastore/new-context db {:title "Imports"} "scraper")))]
        (jdbc/execute-one! db
                           (sql/format {:update [:items]
                                        :set {:human_readable_id [:inline "imports"]}
                                        :where [:= :id [:inline id]]}))
        id)))

(defn- seen?
  [db table column id]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [column]
                                            :from [table]
                                            :where [:= column [:inline id]]})))))

(defn- mark-seen!
  [db table column id]
  (jdbc/execute-one! db
                     (sql/format {:insert-into [table]
                                  :columns [column]
                                  :values [[[:inline id]]]})))

;; --- YouTube channels -------------------------------------------------------

(defn list-channels
  [db]
  (->> (jdbc/execute! db
                      (sql/format {:select [:id :channel_id :name :min_duration_minutes]
                                   :from [:youtube_poll_channels]
                                   :order-by [[:added_at :desc]]}))
       (mapv (fn [row]
               {:id (:youtube_poll_channels/id row)
                :channel-id (:youtube_poll_channels/channel_id row)
                :name (:youtube_poll_channels/name row)
                :min-duration (:youtube_poll_channels/min_duration_minutes row)}))))

(defn- channel-exists?
  [db channel-id]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [:id]
                                            :from [:youtube_poll_channels]
                                            :where [:= :channel_id [:inline channel-id]]})))))

(defn add-channel!
  [db input min-duration]
  (when-let [channel-id (youtube-feed/resolve-channel-id input)]
    (when-not (channel-exists? db channel-id)
      (let [title (try (:title (youtube-feed/fetch-channel channel-id)) (catch Exception _ nil))]
        (jdbc/execute-one! db
                           (sql/format {:insert-into [:youtube_poll_channels]
                                        :columns [:channel_id :name :min_duration_minutes]
                                        :values [[[:inline channel-id] [:inline title]
                                                  [:inline min-duration]]]}))))))

(defn update-channel-duration!
  [db id min-duration]
  (jdbc/execute-one! db
                     (sql/format {:update [:youtube_poll_channels]
                                  :set {:min_duration_minutes [:inline min-duration]}
                                  :where [:= :id [:inline id]]})))

(defn delete-channel!
  [db id]
  (jdbc/execute-one! db
                     (sql/format {:delete-from [:youtube_poll_channels]
                                  :where [:= :id [:inline id]]})))

(defn- too-short?
  [url min-duration]
  (when (and min-duration (pos? min-duration))
    (when-let [minutes (youtube-feed/video-duration-minutes url)]
      (< minutes min-duration))))

(defn- poll-channels!
  [db imports-id]
  (doseq [{:keys [channel-id name min-duration]} (list-channels db)]
    (try
      (doseq [{:keys [video-id]} (:videos (youtube-feed/fetch-channel channel-id))]
        (let [url (str "https://www.youtube.com/watch?v=" video-id)]
          (try
            (when-not (seen? db :youtube_poll_seen :video_id video-id)
              (if (too-short? url min-duration)
                (do (log/info (str "poll: skipping short video " url))
                    (mark-seen! db :youtube_poll_seen :video_id video-id))
                (do (log/info (str "poll: importing " url " from " (or name channel-id)))
                    (youtube-insertion/ingest db url #{imports-id} nil)
                    (mark-seen! db :youtube_poll_seen :video_id video-id))))
            (catch Exception e
              (log/error e (str "poll: failed importing " url))))))
      (catch Exception e
        (log/error e (str "poll: failed polling channel " channel-id))))))

;; --- Atom feeds -------------------------------------------------------------

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
        (let [title (try (:title (atom-feed/fetch-feed feed-url)) (catch Exception _ nil))]
          (jdbc/execute-one! db
                             (sql/format {:insert-into [:atom_poll_feeds]
                                          :columns [:feed_url :name]
                                          :values [[[:inline feed-url] [:inline title]]]})))))))

(defn delete-feed!
  [db id]
  (jdbc/execute-one! db
                     (sql/format {:delete-from [:atom_poll_feeds]
                                  :where [:= :id [:inline id]]})))

(defn- fill-description!
  [db item summary]
  (when (and (map? item)
             (:id item)
             (not (:previously-existing-item? item))
             (str/blank? (:description item))
             (seq summary))
    (datastore/update-context-description db {:id (:id item) :description summary} "scraper")))

(defn- poll-feeds!
  [db imports-id]
  (doseq [{:keys [feed-url name]} (list-feeds db)]
    (try
      (doseq [{:keys [entry-id link summary]} (:entries (atom-feed/fetch-feed feed-url))]
        (try
          (when (and link (not (seen? db :atom_poll_seen :entry_id entry-id)))
            (log/info (str "poll: importing " link " from " (or name feed-url)))
            (let [item (insertion/insert-item db link {:id imports-id} nil "scraper")]
              (fill-description! db item summary))
            (mark-seen! db :atom_poll_seen :entry_id entry-id))
          (catch Exception e
            (log/error e (str "poll: failed importing " link)))))
      (catch Exception e
        (log/error e (str "poll: failed polling feed " feed-url))))))

;; --- Scheduler --------------------------------------------------------------

(defn poll-once!
  [db]
  (let [imports-id (ensure-imports-context! db)]
    (poll-channels! db imports-id)
    (poll-feeds! db imports-id)))

(defonce ^:private scheduler (atom nil))

(defn start-scheduler!
  [db]
  (when-not @scheduler
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleAtFixedRate
        exec
        ^Runnable (fn []
                    (try (poll-once! db)
                         (catch Throwable e (log/error e "poll: tick failed"))))
        (long initial-delay-seconds)
        (long (* 60 interval-minutes))
        TimeUnit/SECONDS)
      (reset! scheduler exec)
      (log/info (str "poll: scheduler started (every " interval-minutes " min)")))))

(defn stop-scheduler!
  []
  (when-let [exec @scheduler]
    (.shutdownNow exec)
    (reset! scheduler nil)))

;; --- Dispatch handlers ------------------------------------------------------

(defn list-youtube-poll-channels
  [{:keys [db]}]
  (fn [_state] {:youtube-poll-channels (list-channels db)}))

(defn add-youtube-poll-channel
  [{:keys [db]}]
  (fn [_state input min-duration]
    (add-channel! db input min-duration)
    {:youtube-poll-channels (list-channels db)}))

(defn update-youtube-poll-channel
  [{:keys [db]}]
  (fn [_state id min-duration]
    (update-channel-duration! db id min-duration)
    {:youtube-poll-channels (list-channels db)}))

(defn delete-youtube-poll-channel
  [{:keys [db]}]
  (fn [_state id]
    (delete-channel! db id)
    {:youtube-poll-channels (list-channels db)}))

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
