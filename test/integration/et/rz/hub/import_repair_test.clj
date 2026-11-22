(ns et.rz.hub.import-repair-test
  "The batch import repairs names in place and leaves alone what it cannot
   file (tracker 1823, the owner's 'fix it in place during ingest'):

   - names: NFC, / and control characters to -, spaces off the ends, at most
     255 bytes -- on disk and in the db alike, each repair logged;
   - still arriving (temporaries, dotfiles, just written): skipped quietly;
   - not what the suffix says (empty, wrong first bytes): left in imports,
     with the reason in the log;
   - two names that repair to one: the second is refused, as before.

   On real files in /tmp, not under /workspace: in the rhizome box that is a
   Mac's APFS, which takes NFC and NFD for one name. Only the db write
   (file/ingest) is stubbed, and records the name it would have stored."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [et.rz.config :as config]
            [et.rz.names :as names]
            [et.rz.hub.repository.insertion.arrival :as arrival]
            [et.rz.hub.repository.insertion.batch :as batch]
            [et.rz.hub.repository.insertion.common :as common]
            [et.rz.hub.repository.insertion.file :as file]
            [et.rz.hub.repository.homefolder :as home])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private pdf (.getBytes "%PDF-1.4\n%%EOF\n" "ISO-8859-1"))
(def ^:private mp3 (byte-array (concat [(unchecked-byte 0xff) (unchecked-byte 0xfb) (unchecked-byte 0x90) (unchecked-byte 0xc0)] (repeat 60 0))))

(def ^:dynamic *root* nil)

(defn- folder [k] (str (io/file *root* (name k))))

(use-fixtures :each
  (fn [t]
    (let [root (.toFile (Files/createTempDirectory "rhizome-import-test" (make-array FileAttribute 0)))]
      (binding [*root* root
                ;; a short look, for speed; the test of a growing file sets its own
                arrival/*look-ms* 100]
        (doseq [k [:imports :docs :audio :video :images]] (.mkdirs (io/file (folder k))))
        (try (t)
             (finally (doseq [^java.io.File f (reverse (file-seq root))] (.delete f))))))))

(def ^:private an-hour-ago (- (System/currentTimeMillis) 3600000))

(defn- arrive!
  "A file in imports, `bytes` in it, last touched an hour ago unless `fresh?`."
  ([file-name bytes] (arrive! file-name bytes false))
  ([file-name ^bytes bytes fresh?]
   (let [f (io/file (folder :imports) file-name)]
     (with-open [o (io/output-stream f)] (.write o bytes))
     (when-not fresh? (.setLastModified f an-hour-ago))
     f)))

(defn- in [k] (set (.list (io/file (folder k)))))

(defn- import!
  "Run the batch import; the names it would have stored in the db, in order."
  []
  (let [stored (atom [])]
    (with-redefs [config/config (update config/config :folders merge
                                        (into {} (map (fn [k] [k (folder k)])) [:imports :docs :audio :video :images]))
                  common/get-item-or-throw-error (constantly 1)
                  ;; the db write stubbed, but not the check it starts with:
                  ;; the "File already exists" refusal is file/ingest's own
                  file/ingest (fn [_ n _ _] (home/validate-not-exists n) (swap! stored conj n))]
      (batch/ingest nil "IMPORT" nil nil))
    @stored))

(deftest names-are-repaired-on-disk-and-in-the-db
  (arrive! "  spaced out.pdf  " pdf)
  (arrive! "tab\there.pdf" pdf)
  (arrive! "bell\u0007.pdf" pdf)
  (let [stored (set (import!))]
    (is (= #{"spaced out.pdf" "tab-here.pdf" "bell-.pdf"} stored))
    (is (= stored (in :docs)))
    (is (empty? (in :imports)))))

(deftest an-nfd-name-is-filed-as-nfc
  (if-not (names/utf-8-paths?)
    (println "SKIPPED (sun.jnu.encoding" (names/path-encoding) "-- run under LC_ALL=C.UTF-8): an-nfd-name-is-filed-as-nfc")
    (let [nfd (names/nfd "Müller.pdf")]
      (arrive! nfd pdf)
      (is (= [(names/nfc nfd)] (import!)))
      (is (= #{(names/nfc nfd)} (in :docs))))))

(deftest still-arriving-is-left
  (doseq [n [".syncthing.a.pdf.tmp" "~syncthing~b.pdf.tmp" "c.pdf.part" "d.pdf.crdownload"
             "e.pdf.download" "f.pdf.tmp" ".DS_Store" "._g.pdf"]]
    (arrive! n pdf))
  (arrive! "done.pdf" pdf)
  (let [growing (arrive! "being written.pdf" pdf)
        writer (future
                 (dotimes [_ 6]
                   (Thread/sleep 50)
                   (with-open [o (io/output-stream growing :append true)] (.write o (byte-array 10)))))]
    (is (= ["done.pdf"] (binding [arrival/*look-ms* 400] (import!))))
    @writer)
  (is (= #{"done.pdf"} (in :docs)))
  (is (= 9 (count (in :imports))))
  (testing "taken by the next import once it has settled"
    (is (= ["being written.pdf"] (import!)))))

(deftest a-file-dated-in-the-future-is-imported
  ;; Second review, finding 2: Syncthing keeps the source's modification time,
  ;; and a wrong clock on another Mac dates a whole file ahead. It was "just
  ;; written" until that date, and not imported, silently.
  (doto (arrive! "from the future.pdf" pdf)
    (.setLastModified (+ (System/currentTimeMillis) (* 3600 1000 24 30))))
  (is (= ["from the future.pdf"] (import!))))

(deftest not-what-the-suffix-says-stays-in-imports
  (arrive! "empty.pdf" (byte-array 0))
  (arrive! "not really.mp3" (.getBytes "hello, not a sound"))
  (arrive! "a sound.mp3" mp3)
  (is (= ["a sound.mp3"] (import!)))
  (is (= #{"empty.pdf" "not really.mp3"} (in :imports)))
  (is (= #{"a sound.mp3"} (in :audio))))

(deftest an-unreadable-file-does-not-stop-the-import
  ;; Second review, finding 1: the content check read the file outside the
  ;; batch's try, and one unreadable file ended the whole run, every run.
  ;; Listed before the others (list order is the directory's), and readable
  ;; to nobody -- as Syncthing's 0600 for another user is to the hub.
  (let [locked (arrive! "aaa locked.pdf" pdf)]
    (arrive! "zzz fine.pdf" pdf)
    (arrive! "mmm fine too.pdf" pdf)
    (.setReadable locked false false)
    (if (.canRead locked)
      (println "SKIPPED (running as root, which reads anything): an-unreadable-file-does-not-stop-the-import")
      (do
        (is (= #{"zzz fine.pdf" "mmm fine too.pdf"} (set (import!))))
        (is (= #{"aaa locked.pdf"} (in :imports)))
        (.setReadable locked true false)))))

(deftest a-name-that-repairs-to-a-dotfile-is-left
  ;; Second review, finding 3: trimming made these hidden files with an empty
  ;; title. Left for the human to name.
  (arrive! "   .pdf" pdf)
  (arrive! " .hidden.jpg" (byte-array (map unchecked-byte [0xff 0xd8 0xff 0xe0 0])))
  (arrive! "kept.pdf" pdf)
  (is (= ["kept.pdf"] (import!)))
  (is (= #{"   .pdf" " .hidden.jpg"} (in :imports)))
  (is (empty? (in :images))))

(deftest two-names-that-repair-to-one
  ;; Today's behaviour, kept: the second is refused ("File already exists")
  ;; and stays in imports -- under its repaired name, which it was given
  ;; before the refusal. Nothing is overwritten, nothing filed twice.
  (arrive! "same.pdf" pdf)
  (arrive! " same.pdf" pdf)
  (let [stored (import!)]
    (is (= ["same.pdf"] stored))
    (is (= #{"same.pdf"} (in :docs)))
    (is (= 1 (count (in :imports))))))

;; ## The pieces

(deftest repair
  (is (= "Jürgen.pdf" (names/repair "Jürgen.pdf")))
  (is (= "a-b-c.pdf" (names/repair " a/b\u0000c.pdf ")))
  (is (= (names/nfc "Müller.pdf") (names/repair "Müller.pdf")))
  (testing "over 255 bytes: cut by whole characters, suffix kept"
    (let [long-name (str (apply str (repeat 200 "ü")) ".pdf")
          r (names/repair long-name)]
      (is (<= (alength (.getBytes r "UTF-8")) 255))
      (is (str/ends-with? r "ü.pdf"))
      (is (= r (names/nfc r)))))
  (testing "a letter with no precomposed form keeps its mark"
    ;; q + combining diaeresis: two code points, one character, no NFC form
    (let [r (names/repair (str (apply str (repeat 200 "q̈")) ".pdf"))]
      (is (<= (alength (.getBytes r "UTF-8")) 255))
      (is (str/ends-with? r "q̈.pdf"))))
  (testing "trailing space left by the cut goes too"
    (let [r (names/repair (str (apply str (repeat 250 "a")) " bbbbbbbbbbbb.pdf"))]
      (is (not (str/includes? r " .pdf"))))))

(deftest unusable
  (is (nil? (names/unusable "a.pdf")))
  (is (nil? (names/unusable "a.b.pdf")))
  (is (re-find #"dot" (names/unusable ".pdf")))
  (is (re-find #"dot" (names/unusable ".hidden.jpg")))
  (is (re-find #"nothing" (names/unusable "")))
  (is (re-find #"nothing" (names/unusable "   "))))

(deftest content-checks
  (let [f (fn [file-name bytes]
            (let [file (io/file *root* "probe")]
              (with-open [o (io/output-stream file)] (.write o ^bytes bytes))
              (arrival/content-problem file file-name)))
        b (fn [& xs] (byte-array (map unchecked-byte xs)))
        s (fn [^String x] (.getBytes x "ISO-8859-1"))]
    (doseq [[n bytes] [["a.jpg" (b 0xff 0xd8 0xff 0xe0 0)]
                       ["a.JPEG" (b 0xff 0xd8 0xff 0xe1 0)]
                       ["a.png" (s "\u0089PNG\r\n\u001a\n....")]
                       ["a.webp" (s "RIFF....WEBPVP8 ")]
                       ["a.pdf" (s "junk before %PDF-1.7")]
                       ["a.mp4" (s "\u0000\u0000\u0000\u0018ftypisom")]
                       ["a.mov" (s "\u0000\u0000\u0000\u0008wide....")]
                       ["a.m4a" (s "\u0000\u0000\u0000 ftypM4A ")]
                       ["a.mp3" (s "ID3\u0003\u0000")]
                       ["a.mp3" mp3]
                       ["a.wav" (s "RIFF....WAVEfmt ")]
                       ["a.ogg" (s "OggS\u0000")]
                       ["a.flv" (s "FLV\u0001")]
                       ["a.tiff" (s "II*\u0000....")]
                       ["a.tiff" (s "MM\u0000*....")]
                       ["a.txt" (s "anything")]]]
      (testing n (is (nil? (f n bytes)))))
    (doseq [[n bytes why] [["a.pdf" (byte-array 0) #"empty"]
                           ["a.png" (s "GIF89a") #"not those of a PNG"]
                           ["a.mp4" (s "<html>") #"MP4"]
                           ["a.jpg" (s "%PDF-1.4") #"JPEG"]]]
      (testing n (is (re-find why (f n bytes)))))))
