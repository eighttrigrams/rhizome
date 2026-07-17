(ns scrapers.youtube-feed
  (:require [clojure.string :as str]
            [clojure.xml :as xml]
            [clj-http.client :as http])
  (:import [java.io ByteArrayInputStream]))

(def ^:private feed-url "https://www.youtube.com/feeds/videos.xml?channel_id=")

(defn- tag-name [el] (when (map? el) (name (:tag el))))

(defn- tag-ends? [suffix el] (boolean (some-> (tag-name el) (str/ends-with? suffix))))

(defn- entry->video
  [entry]
  (let [children (:content entry)
        video-id (some (fn [el] (when (tag-ends? "videoId" el) (first (:content el)))) children)
        title (some (fn [el] (when (= "title" (tag-name el)) (first (:content el)))) children)]
    (when (seq video-id) {:video-id video-id :title title})))

(defn parse-feed
  [xml-string]
  (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes ^String xml-string "UTF-8")))
        title (some (fn [el] (when (= "title" (tag-name el)) (first (:content el))))
                    (:content parsed))
        videos (->> (:content parsed)
                    (filter (fn [el] (= "entry" (tag-name el))))
                    (keep entry->video)
                    vec)]
    {:title title :videos videos}))

(defn fetch-channel
  [channel-id]
  (let [resp (http/get (str feed-url channel-id) {:as :string :throw-exceptions false})]
    (when (= 200 (:status resp))
      (parse-feed (:body resp)))))

(defn resolve-channel-id
  [input]
  (let [input (str/trim (or input ""))]
    (cond
      (re-matches #"UC[\w-]{20,}" input) input
      (second (re-find #"channel/(UC[\w-]{20,})" input)) (second (re-find #"channel/(UC[\w-]{20,})"
                                                                          input))
      (re-find #"youtube\.com|youtu\.be" input)
        (let [resp (http/get input {:as :string
                                    :throw-exceptions false
                                    :headers {"User-Agent" "Mozilla/5.0"}})]
          (when (= 200 (:status resp))
            (second (re-find #"\"channelId\":\"(UC[\w-]{20,})\"" (:body resp)))))
      :else nil)))
