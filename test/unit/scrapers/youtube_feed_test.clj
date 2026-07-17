(ns scrapers.youtube-feed-test
  (:require [clojure.test :refer [deftest is testing]]
            [scrapers.youtube-feed :as feed]))

(def ^:private sample-feed
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" "
       "xmlns:media=\"http://search.yahoo.com/mrss/\" xmlns=\"http://www.w3.org/2005/Atom\">"
       "<title>Example Channel</title>"
       "<yt:channelId>UCexampleexampleexample0</yt:channelId>"
       "<entry>"
       "<id>yt:video:AAAAAAAAAAA</id>"
       "<yt:videoId>AAAAAAAAAAA</yt:videoId>"
       "<title>First Video</title>"
       "<media:group><media:title>First Video media</media:title></media:group>"
       "</entry>"
       "<entry>"
       "<id>yt:video:BBBBBBBBBBB</id>"
       "<yt:videoId>BBBBBBBBBBB</yt:videoId>"
       "<title>Second Video</title>"
       "</entry>"
       "</feed>"))

(deftest parse-feed-test
  (testing "extracts channel title and video entries"
    (let [{:keys [title videos]} (feed/parse-feed sample-feed)]
      (is (= "Example Channel" title))
      (is (= 2 (count videos)))
      (is (= "AAAAAAAAAAA" (:video-id (first videos))))
      (is (= "First Video" (:title (first videos))))
      (is (= "BBBBBBBBBBB" (:video-id (second videos)))))))

(deftest resolve-channel-id-test
  (testing "accepts a raw UC channel id"
    (is (= "UCexampleexampleexample0" (feed/resolve-channel-id "UCexampleexampleexample0"))))
  (testing "extracts a channel id from a channel URL"
    (is (= "UCexampleexampleexample0"
           (feed/resolve-channel-id "https://www.youtube.com/channel/UCexampleexampleexample0"))))
  (testing "returns nil for unrecognized input"
    (is (nil? (feed/resolve-channel-id "not a channel")))))
