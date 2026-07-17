(ns youtube.poll
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [scrapers.youtube-feed :as feed]
            [repository.insertion.youtube :as youtube])
  (:import [java.util.concurrent Executors TimeUnit]))

(def ^:private poll-interval-minutes 5)

(defn list-channels
  [db]
  (->> (jdbc/execute! db
                      (sql/format {:select [:id :channel_id :name]
                                   :from [:youtube_poll_channels]
                                   :order-by [[:added_at :desc]]}))
       (mapv (fn [row]
               {:id (:youtube_poll_channels/id row)
                :channel-id (:youtube_poll_channels/channel_id row)
                :name (:youtube_poll_channels/name row)}))))

(defn- channel-exists?
  [db channel-id]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [:id]
                                            :from [:youtube_poll_channels]
                                            :where [:= :channel_id [:inline channel-id]]})))))

(defn add-channel!
  [db input]
  (when-let [channel-id (feed/resolve-channel-id input)]
    (when-not (channel-exists? db channel-id)
      (let [title (try (:title (feed/fetch-channel channel-id)) (catch Exception _ nil))]
        (jdbc/execute-one! db
                           (sql/format {:insert-into [:youtube_poll_channels]
                                        :columns [:channel_id :name]
                                        :values [[[:inline channel-id] [:inline title]]]}))))))

(defn delete-channel!
  [db id]
  (jdbc/execute-one! db
                     (sql/format {:delete-from [:youtube_poll_channels]
                                  :where [:= :id [:inline id]]})))

(defn- imports-by-title
  [db]
  (try (:id (datastore/get-item-by-title db {:title "Imports"})) (catch Exception _ nil)))

(defn ensure-imports-context!
  [db]
  (or (:id (first (search/find-items-by-ids db {:human-readable-ids ["imports"]})))
      (let [id (or (imports-by-title db) (:id (datastore/new-context db {:title "Imports"})))]
        (jdbc/execute-one! db
                           (sql/format {:update [:items]
                                        :set {:human_readable_id [:inline "imports"]}
                                        :where [:= :id [:inline id]]}))
        id)))

(defn- seen?
  [db video-id]
  (boolean (seq (jdbc/execute! db
                               (sql/format {:select [:video_id]
                                            :from [:youtube_poll_seen]
                                            :where [:= :video_id [:inline video-id]]})))))

(defn- mark-seen!
  [db video-id]
  (jdbc/execute-one! db
                     (sql/format {:insert-into [:youtube_poll_seen]
                                  :columns [:video_id]
                                  :values [[[:inline video-id]]]})))

(defn poll-once!
  [db]
  (let [imports-id (ensure-imports-context! db)]
    (doseq [{:keys [channel-id name]} (list-channels db)]
      (try
        (doseq [{:keys [video-id]} (:videos (feed/fetch-channel channel-id))]
          (let [url (str "https://www.youtube.com/watch?v=" video-id)]
            (try
              (when-not (seen? db video-id)
                (log/info (str "youtube-poll: importing " url " from " (or name channel-id)))
                (youtube/ingest db url #{imports-id} nil)
                (mark-seen! db video-id))
              (catch Exception e
                (log/error e (str "youtube-poll: failed importing " url))))))
        (catch Exception e
          (log/error e (str "youtube-poll: failed polling channel " channel-id)))))))

(defonce ^:private scheduler (atom nil))

(defn start-scheduler!
  [db]
  (when-not @scheduler
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleAtFixedRate
        exec
        ^Runnable (fn []
                    (try (poll-once! db)
                         (catch Throwable e (log/error e "youtube-poll: tick failed"))))
        (long 30)
        (long (* 60 poll-interval-minutes))
        TimeUnit/SECONDS)
      (reset! scheduler exec)
      (log/info (str "youtube-poll: scheduler started (every " poll-interval-minutes " min)")))))

(defn stop-scheduler!
  []
  (when-let [exec @scheduler]
    (.shutdownNow exec)
    (reset! scheduler nil)))

(defn list-youtube-poll-channels
  [{:keys [db]}]
  (fn [_state] {:youtube-poll-channels (list-channels db)}))

(defn add-youtube-poll-channel
  [{:keys [db]}]
  (fn [_state input]
    (add-channel! db input)
    {:youtube-poll-channels (list-channels db)}))

(defn delete-youtube-poll-channel
  [{:keys [db]}]
  (fn [_state id]
    (delete-channel! db id)
    {:youtube-poll-channels (list-channels db)}))
