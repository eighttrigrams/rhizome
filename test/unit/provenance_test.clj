(ns provenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [et.uvt.caution :as uvt]
            [provenance :as provenance]))

;; `ds/get-description-history` returns versions newest first, so every fixture
;; here is written that way -- head is the current description.

(defn- v [text source] {:text text :source source})

(defn- ranges [versions] (:ranges (provenance/of-versions versions)))

(deftest of-versions-reverses-the-history-test
  (testing "the owner writes, an agent replaces the lot: the lines are the agent's"
    ;; Both versions are three lines and the second replaces all three, so the
    ;; two possible readings of this history differ in nothing but the number
    ;; they attach. That is the point of the fixture: getting the order wrong
    ;; does not throw and does not produce a malformed answer, it produces this
    ;; same well-formed range with the value inverted.
    (let [newest-first [(v "their one\ntheir two\ntheir three" "api")
                        (v "his one\nhis two\nhis three" "app")]]
      (is (= [{:from 1 :to 3 :caution 0.0}] (ranges newest-first)))
      (is (= [{:from 1 :to 3 :caution 1.0}]
             (uvt/assess newest-first {:ours provenance/ours}))
          (str "handed over unreversed, the same history says the agent's lines "
               "are the owner's own hand -- an answer nothing but this "
               "comparison would flag as wrong"))))
  (testing "the owner writes, an agent appends: only the appended lines are free"
    (let [newest-first [(v "his line\ntheir one\ntheir two\ntheir three" "api")
                        (v "his line" "app")]]
      (is (= [{:from 1 :to 1 :caution 1.0}
              {:from 2 :to 4 :caution 0.0}]
             (ranges newest-first)))))
  (testing "the oldest version is the one the fold starts from, over three"
    (let [newest-first [(v "his one\nhis two\ntheir three" "api")
                        (v "his one\nhis two\nhis three" "app")
                        (v "his one\nhis two" "app")]]
      ;; His island survives both later versions; the agent's line lands at the
      ;; end of it and dilutes it rather than cutting it in two.
      (is (= [{:from 1 :to 3 :caution (double (/ 2 3))}] (ranges newest-first))))))

(deftest source-of-a-row-that-predates-the-column-test
  (testing "a nil source is read as the owner's, not as an agent's"
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (ranges [(v "written before there was a source column\nsecond line" nil)]))))
  (testing "a blank source is a null by another spelling"
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (ranges [(v "written before there was a source column\nsecond line" "")])))
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (ranges [(v "written before there was a source column\nsecond line" "   ")]))))
  (testing "the same text stamped by an agent is the other end of the scale"
    ;; The contrast is the whole content of the decision: these two fixtures
    ;; differ in one absent column and land at opposite ends.
    (is (= [{:from 1 :to 2 :caution 0.0}]
           (ranges [(v "written before there was a source column\nsecond line" "api")]))))
  (testing "an unsourced version among sourced ones counts as his throughout"
    (is (= [{:from 1 :to 1 :caution 1.0}
            {:from 2 :to 2 :caution 0.0}]
           (ranges [(v "an old line\na scraped line" "scraper")
                    (v "an old line" nil)])))))

(deftest scraper-is-theirs-test
  (testing "a scraped description is as free to edit as an agent's"
    (is (= [{:from 1 :to 2 :caution 0.0}]
           (ranges [(v "scraped summary\nsecond line" "scraper")])))))

(deftest no-description-to-be-careful-in-test
  (testing "nil rather than ranges over a description that is not there"
    (is (nil? (provenance/of-versions [])))
    (is (nil? (provenance/of-versions nil)))
    (is (nil? (provenance/of-versions [(v nil "app")]))
        "an item whose history is about its title, not its description")
    (is (nil? (provenance/of-versions [(v "" "app") (v "since deleted" "app")]))
        "a description that has been emptied has no lines left to attribute")))

(deftest ranges-cover-a-trailing-empty-line-test
  ;; The library counts a trailing newline as a line and the client has to split
  ;; the same way (`#"\n" -1`, not split-lines) or it draws one row short. This
  ;; pins the server end of that agreement.
  (testing "a body ending in a newline is n+1 lines"
    (is (= 3 (:to (last (ranges [(v "one\ntwo\n" "app")])))))
    (is (= 1 (:from (first (ranges [(v "one\ntwo\n" "app")])))))))

(deftest legend-travels-with-the-ranges-test
  (testing "both keys, on every answer that has ranges at all"
    (let [answer (provenance/of-versions [(v "a line" "app")])]
      (is (= #{:legend :ranges} (set (keys answer))))
      (is (= provenance/legend (:legend answer))))))

(def ^:private legend provenance/legend)

(defn- clause
  "The stretch of the legend from `from` up to `to` (exclusive)."
  [from to]
  (let [i (str/index-of legend from)
        j (str/index-of legend to)]
    (is (and i j (< i j)) (str "legend has lost its shape around " (pr-str from)))
    (subs legend i j)))

(deftest legend-pins-each-end-to-its-author-test
  ;; A legend that reads the spectrum backwards is a correct set of ranges with
  ;; a lie attached to it, and no other test in this file would notice.
  (let [sacred (clause "1.00 is" "0.00 is")
        free (clause "0.00 is" "In between")]
    (testing "1.00 is the web UI's end, and says to leave it alone"
      (is (str/includes? sacred "\"app\""))
      (is (not (str/includes? sacred "\"api\"")))
      (is (not (str/includes? sacred "\"scraper\"")))
      (is (str/includes? sacred "not yours to rewrite")))
    (testing "0.00 is the agents' end, and says it may be edited"
      (is (str/includes? free "\"api\""))
      (is (str/includes? free "\"scraper\""))
      (is (not (str/includes? free "\"app\"")))
      (is (str/includes? free "free to edit")))
    (testing "the vocabulary is rhizome's own, not the library's examples"
      (is (not (str/includes? legend "machine")))
      (is (not (str/includes? legend "\"ui\""))))))
