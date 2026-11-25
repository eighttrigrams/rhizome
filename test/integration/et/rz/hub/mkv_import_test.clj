(ns et.rz.hub.mkv-import-test
  "Matroska (.mkv) files are imported like mp4s: an item under Imports, Files,
   Video and MKVs, the file moved byte for byte into the video folder (the
   owner keeps them for their subtitle tracks, so nothing is converted).

   And the MKVs context, which databases made before it lack: the hub creates
   it at startup (file/add-contexts!), once, so the gate (file/ensure-contexts!)
   passes on the old db with no hand step on deploy.

   Against the real test db, seeded the way a fresh db is."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [et.rz.config :as config]
            [et.rz.hub.dev-seed :as dev-seed]
            [et.rz.hub.ds :as ds]
            [et.rz.hub.ds.search-test :refer [db reset-db]]
            [et.rz.hub.main :as hub-main]
            [et.rz.hub.repository.insertion.arrival :as arrival]
            [et.rz.hub.repository.insertion.batch :as batch]
            [et.rz.hub.repository.insertion.file :as file]
            [next.jdbc :as jdbc])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private folder-keys [:imports :docs :audio :video :images])

(def ^:dynamic *root* nil)

(defn- folder [k] (io/file *root* (name k)))

(use-fixtures :each
  (fn [t]
    (let [root (.toFile (Files/createTempDirectory "rhizome-mkv-test" (make-array FileAttribute 0)))]
      (binding [*root* root
                arrival/*look-ms* 50]
        (doseq [k folder-keys] (.mkdirs (folder k)))
        (reset-db)
        (is (= :seeded (dev-seed/maybe-seed! {:db db})))
        (try (t)
             (finally (doseq [^java.io.File f (reverse (file-seq root))] (.delete f))))))))

(def ^:private matroska
  "The start of a real Matroska file: the EBML header, then some of the rest."
  (byte-array (map unchecked-byte
                   (concat [0x1a 0x45 0xdf 0xa3 0xa3 0x42 0x86 0x81 0x01]
                           (.getBytes "matroska" "ISO-8859-1")
                           (range 0 256)))))

(defn- arrive! [file-name ^bytes bytes]
  (let [f (io/file (folder :imports) file-name)]
    (with-open [o (io/output-stream f)] (.write o bytes))
    (.setLastModified f (- (System/currentTimeMillis) 3600000))
    f))

(defn- import! []
  (with-redefs [config/config (update config/config :folders merge
                                      (into {} (map (fn [k] [k (str (folder k))])) folder-keys))]
    (batch/ingest db "IMPORT" nil nil)))

(defn- named-ids-of
  "The named ids of the contexts the item titled `title` is filed under, and
   the titles of those without one."
  [title]
  (let [{:items/keys [id]} (jdbc/execute-one! db ["SELECT id FROM items WHERE title = ? AND is_context = 0" title])]
    (when id
      (->> (jdbc/execute! db ["SELECT i.title, i.human_readable_id FROM relations r
                               JOIN items i ON i.id = r.owner_id WHERE r.target_id = ?" id])
           (map (fn [{:items/keys [title human_readable_id]}] (or human_readable_id title)))
           set))))

(def ^:private episode "The.Wire.S01E01.DVDRip.DD5.1.x264-DBO")

(deftest an-mkv-is-named-filed-and-moved
  (arrive! (str episode ".mkv") matroska)
  (import!)
  (testing "named after the file, filed under Imports, Files, Video and MKVs"
    (is (= #{"Imports" "files" "video" "mkv"} (named-ids-of episode))))
  (testing "its file is the mkv, and the item shows the badge the mirror gives it"
    (let [{:items/keys [id]} (jdbc/execute-one! db ["SELECT id FROM items WHERE title = ?" episode])
          item (ds/get-item db {:id id})]
      (is (= (str episode ".mkv") (get-in item [:data :resource-links :file])))
      (is (nil? (get-in item [:data :resource-links :image])))
      (is (some #{"MKVs"} (map :title (vals (get-in item [:data :contexts])))))))
  (testing "moved out of imports into the video folder, byte for byte"
    (is (empty? (.list (folder :imports))))
    (let [moved (io/file (folder :video) (str episode ".mkv"))]
      (is (.exists moved))
      (is (= (seq matroska) (seq (Files/readAllBytes (.toPath moved))))))))

(deftest an-upper-case-suffix-is-an-mkv-too
  (arrive! "Shouting.MKV" matroska)
  (import!)
  (is (= #{"Imports" "files" "video" "mkv"} (named-ids-of "Shouting")))
  (is (.exists (io/file (folder :video) "Shouting.MKV"))))

(deftest a-fake-mkv-stays-in-imports
  (arrive! "not a film.mkv" (.getBytes "<html>a download page</html>" "ISO-8859-1"))
  (arrive! "an mp4 called mkv.mkv" (.getBytes "\u0000\u0000\u0000\u0018ftypisom" "ISO-8859-1"))
  (arrive! "empty.mkv" (byte-array 0))
  (import!)
  (is (= #{"not a film.mkv" "an mp4 called mkv.mkv" "empty.mkv"} (set (.list (folder :imports)))))
  (is (empty? (.list (folder :video))))
  (is (nil? (named-ids-of "not a film")))
  (testing "and the reason is the one the log gives"
    (let [probe (io/file (folder :imports) "not a film.mkv")]
      (is (re-find #"Matroska" (arrival/content-problem probe "not a film.mkv"))))))

;; ## The startup step, on a db made before MKVs

(defn- mkv-rows []
  (jdbc/execute! db ["SELECT id, title, short_title, is_context FROM items WHERE human_readable_id = 'mkv'"]))

(defn- drop-named! [named-id]
  (jdbc/execute-one! db ["DELETE FROM items WHERE human_readable_id = ?" named-id]))

(deftest the-startup-step-adds-mkvs-to-an-old-db-once
  (drop-named! "mkv")
  (testing "an old db: the gate refuses it as it stands"
    (is (= ["mkv"] (vec (file/missing-contexts db))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mkv" (file/ensure-contexts! db))))
  (testing "the step creates MKVs, short title mkv, and the gate passes"
    (is (= #{"mkv"} (file/add-contexts! db)))
    (is (= [{:items/title "MKVs" :items/short_title "mkv" :items/is_context 1}]
           (map #(dissoc % :items/id) (mkv-rows))))
    (is (empty? (file/missing-contexts db)))
    (is (nil? (file/ensure-contexts! db))))
  (testing "idempotent: a second start creates nothing"
    (is (= #{} (file/add-contexts! db)))
    (is (= 1 (count (mkv-rows)))))
  (testing "and an mkv imported then is filed under it"
    (arrive! "after the deploy.mkv" matroska)
    (import!)
    (is (= #{"Imports" "files" "video" "mkv"} (named-ids-of "after the deploy")))))

(deftest the-hub-comes-up-on-an-old-db
  ;; What -main runs before it serves, as it runs it: an old db (no MKVs) is
  ;; given the context and passes the gate; a second start changes nothing.
  (drop-named! "mkv")
  (hub-main/prepare-contents! db {:dev? false})
  (is (= 1 (count (mkv-rows))))
  (hub-main/prepare-contents! db {:dev? false})
  (is (= 1 (count (mkv-rows))))
  (testing "while an older type gone missing still stops it"
    (drop-named! "mp4")
    (is (thrown? clojure.lang.ExceptionInfo (hub-main/prepare-contents! db {:dev? false})))))

(deftest an-mkvs-context-made-by-hand-is-given-the-named-id
  (drop-named! "mkv")
  (let [own (:id (ds/new-context db {:title "MKVs"}))]
    (is (= #{"mkv"} (file/add-contexts! db)))
    (is (= [own] (map :items/id (mkv-rows))) "no twin: the owner's context is the one")
    (is (= 1 (:n (jdbc/execute-one! db ["SELECT count(*) AS n FROM items WHERE title = 'MKVs'"]
                                    {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))))))

(deftest a-lost-older-context-is-still-the-gates-to-refuse
  ;; add-contexts! creates only the types added since (file/added-contexts):
  ;; a lost MP4s means files are filed somewhere the owner no longer looks.
  (drop-named! "mp4")
  (is (= #{} (file/add-contexts! db)))
  (is (= ["mp4"] (vec (file/missing-contexts db))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mp4" (file/ensure-contexts! db))))
