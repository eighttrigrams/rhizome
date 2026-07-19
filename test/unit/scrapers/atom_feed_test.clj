(ns scrapers.atom-feed-test
  (:require [clojure.test :refer [deftest is testing]]
            [scrapers.atom-feed :as feed]))

(def ^:private sample-feed
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<feed xml:lang=\"en-us\" xmlns=\"http://www.w3.org/2005/Atom\">"
       "<title>Simon Willison's Weblog</title>"
       "<link href=\"http://example.net/\" rel=\"alternate\"/>"
       "<link href=\"http://example.net/atom/everything/\" rel=\"self\"/>"
       "<id>http://example.net/</id>"
       "<entry>"
       "<title>First Post</title>"
       "<link href=\"https://example.net/2026/Jul/19/first/#atom-everything\" rel=\"alternate\"/>"
       "<published>2026-07-19T05:06:21+00:00</published>"
       "<updated>2026-07-19T05:06:21+00:00</updated>"
       "<id>https://example.net/2026/Jul/19/first/#atom-everything</id>"
       "<summary type=\"html\">&lt;p&gt;Read &lt;a href=\"https://example.org/x\"&gt;this&lt;/a&gt;"
       " with &lt;strong&gt;emphasis&lt;/strong&gt;.&lt;/p&gt;"
       "&lt;blockquote&gt;&lt;p&gt;quoted&lt;/p&gt;&lt;/blockquote&gt;</summary>"
       "</entry>"
       "<entry>"
       "<title>Second Post</title>"
       "<link href=\"https://example.net/2026/Jul/18/second/\" rel=\"alternate\"/>"
       "<updated>2026-07-18T00:00:00+00:00</updated>"
       "<id>https://example.net/2026/Jul/18/second/</id>"
       "</entry>"
       "</feed>"))

(deftest parse-feed-test
  (testing "extracts feed title and entries"
    (let [{:keys [title entries]} (feed/parse-feed sample-feed)]
      (is (= "Simon Willison's Weblog" title))
      (is (= 2 (count entries)))
      (is (= {:entry-id "https://example.net/2026/Jul/19/first/#atom-everything"
              :title "First Post"
              :link "https://example.net/2026/Jul/19/first/#atom-everything"
              :published "2026-07-19T05:06:21+00:00"
              :summary (str "Read [this](https://example.org/x) with **emphasis**.\n\n"
                            "> quoted")}
             (first entries)))))
  (testing "falls back to updated when published is missing"
    (let [{:keys [entries]} (feed/parse-feed sample-feed)]
      (is (= "2026-07-18T00:00:00+00:00" (:published (second entries))))
      (is (nil? (:summary (second entries)))))))
