(ns et.rz.names-test
  "Umlauts in file names: a file on disk in NFC and one in NFD, each asked for
   in both forms, through every lookup (et.rz.names, /files/, /imgs/*,
   homefolder/get-target); an import that files an NFD name as NFC; and the
   start-up check of the JVM's path encoding.

   The tests that need a file with such a name on disk run only where the JVM
   can name one -- under a UTF-8 locale (`LC_ALL=C.UTF-8 clj -M:test ...`).
   Under a POSIX locale no form of the name can be found, which is what
   `check-path-encoding!` is there to say, and they print that they were
   skipped rather than pass on nothing."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [et.rz.config :as config]
            [et.rz.names :as names]
            [et.rz.server.files :as files]
            [et.rz.hub.repository.homefolder :as home]
            [et.rz.hub.repository.insertion.batch :as batch]
            [et.rz.hub.repository.insertion.common :as common]
            [et.rz.hub.repository.insertion.file :as file])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private nfc-name "Brief an Jürgen.pdf")
(def ^:private nfd-name "Notiz für Müller.pdf")

(deftest the-two-forms
  (is (= "Jürgen" (names/nfc "Jürgen")))
  (is (= "Jürgen" (names/nfd "Jürgen")))
  (is (not= (names/nfc nfd-name) nfd-name))
  (testing "NFC first, then NFD"
    (is (= [(names/nfc nfd-name) nfd-name] (names/forms nfd-name)))
    (is (= [nfc-name (names/nfd nfc-name)] (names/forms nfc-name))))
  (testing "a name with nothing to compose is tried once"
    (is (= ["plain.pdf"] (names/forms "plain.pdf")))))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [t]
    (let [root (.toFile (Files/createTempDirectory "rhizome-names-test" (make-array FileAttribute 0)))]
      (binding [*root* root]
        (try (t)
             (finally (doseq [^java.io.File f (reverse (file-seq root))] (.delete f))))))))

(defn- folder [k] (str (io/file *root* (name k))))

(defn- put!
  "Write a file under folder `k` with exactly the bytes of `file-name`."
  [k file-name]
  (let [f (io/file (folder k) file-name)]
    (io/make-parents f)
    (spit f file-name)
    f))

(defn- url [file-name]
  (.replace (java.net.URLEncoder/encode ^String file-name "UTF-8") "+" "%20"))

(defmacro ^:private on-utf-8-paths [& body]
  `(if (names/utf-8-paths?)
     (do ~@body)
     (println "SKIPPED (this JVM's sun.jnu.encoding is" (System/getProperty "sun.jnu.encoding")
              "-- run under LC_ALL=C.UTF-8):" ~(str *ns*))))

(defn- separate-spellings?
  "Whether the filesystem under `dir` keeps NFC and NFD apart, as Linux does.
   APFS on a Mac does not: there a file written in one form is found in the
   other, and the two cannot both exist. Probed by making both and counting."
  [dir]
  (let [probe (io/file dir "probe")]
    (.mkdirs probe)
    (spit (io/file probe (names/nfc "\u00fc")) "c")
    (spit (io/file probe (names/nfd "\u00fc")) "d")
    (let [n (count (.list probe))]
      (doseq [^java.io.File f (.listFiles probe)] (.delete f))
      (.delete probe)
      (= 2 n))))

(deftest both-forms-on-disk-each-found-by-both
  (on-utf-8-paths
    (put! :docs nfc-name)
    (put! :docs nfd-name)
    (let [separate? (separate-spellings? *root*)
          ;; Where the filesystem keeps the forms apart (Linux), the very file
          ;; on disk must come back. Where it does not (APFS), the name comes
          ;; back as asked, and it is the same name only up to normalisation.
          same-file? (fn [on-disk ^java.io.File f]
                       (and f (if separate?
                                (= on-disk (.getName f))
                                (= (names/nfc on-disk) (names/nfc (.getName f))))))]
      (when-not separate?
        (println "NOTE: this filesystem takes NFC and NFD for one name (APFS?);"
                 "the cases that need both on disk at once are left out:" (str *root*)))
      (when separate?
        (testing "the disk really holds two different spellings"
          (is (= #{nfc-name nfd-name} (set (.list (io/file (folder :docs))))))
          (is (not (.exists (io/file (folder :docs) (names/nfd nfc-name)))))
          (is (not (.exists (io/file (folder :docs) (names/nfc nfd-name)))))))
      (doseq [[on-disk asked] [[nfc-name nfc-name]
                               [nfc-name (names/nfd nfc-name)]
                               [nfd-name nfd-name]
                               [nfd-name (names/nfc nfd-name)]]]
        (testing (str "on disk " (if (= on-disk nfc-name) "NFC" "NFD")
                      ", asked " (if (= asked (names/nfc asked)) "NFC" "NFD"))
          (testing "resolve-in"
            (is (same-file? on-disk (names/resolve-in (folder :docs) asked))))
          (testing "/files/"
            (let [res ((files/wrap-files (constantly {:status 418}) (constantly {:docs (folder :docs)}))
                       (mock/request :get (str "/files/" (url asked))))]
              (is (= 200 (:status res)))))
          (testing "file-response, as /imgs/* uses it"
            (is (same-file? on-disk (:body (names/file-response asked {:root (folder :docs)})))))
          (testing "homefolder/get-target, behind /open/"
            (with-redefs [config/config (assoc-in config/config [:folders :docs] (folder :docs))]
              (let [target (io/file (home/get-target asked))]
                (is (= (str (io/file (folder :docs))) (.getParent target)))
                (is (same-file? on-disk target))))))))))

(deftest get-target-for-a-file-not-there-yet-is-nfc
  (on-utf-8-paths
    (with-redefs [config/config (assoc-in config/config [:folders :docs] (folder :docs))]
      (is (= (str (io/file (folder :docs) (names/nfc nfd-name))) (home/get-target nfd-name))))))

(defn- with-folders
  "config's :folders pointed at this test's temp folders."
  [f]
  (with-redefs [config/config (update config/config :folders merge
                                      {:imports (folder :imports) :docs (folder :docs)})]
    (f)))

(deftest get-target-stays-inside-the-folder
  ;; Review findings 2 and 4: /open/ hands its name straight to get-target,
  ;; whose fallback for a name not on disk joined it onto the folder unguarded.
  (spit (io/file *root* "x.pdf") "outside")
  (.mkdirs (io/file (folder :docs)))
  (with-folders
    #(doseq [asked ["../x.pdf" "../../x.pdf" "../nowhere.pdf" (str (io/file *root* "x.pdf")) "/etc/x.pdf"]]
       (testing asked
         (is (nil? (home/get-target asked)))))))

(deftest ren-does-not-overwrite
  ;; Review finding 3: File.renameTo overwrites on Linux.
  (put! :imports "a.pdf")
  (put! :imports "b.pdf")
  (with-folders
    #(do
       (is (false? (home/ren "a.pdf" "b.pdf")))
       (is (= "a.pdf" (slurp (io/file (folder :imports) "a.pdf"))))
       (is (= "b.pdf" (slurp (io/file (folder :imports) "b.pdf"))))
       (testing "to a free name it renames"
         (is (true? (home/ren "a.pdf" "c.pdf")))
         (is (.exists (io/file (folder :imports) "c.pdf")))))))

(deftest move-file-does-not-overwrite
  (put! :imports "a.pdf")
  (put! :docs "a.pdf")
  (spit (io/file (folder :docs) "a.pdf") "the one already filed")
  (with-folders
    #(do
       (is (thrown-with-msg? Exception #"already exists" (home/move-file "a.pdf")))
       (is (= "the one already filed" (slurp (io/file (folder :docs) "a.pdf"))))
       (is (.exists (io/file (folder :imports) "a.pdf")))
       (testing "to a free place it moves"
         (put! :imports "b.pdf")
         (is (nil? (home/move-file "b.pdf")))
         (is (.exists (io/file (folder :docs) "b.pdf")))
         (is (not (.exists (io/file (folder :imports) "b.pdf"))))))))

(deftest an-import-whose-rename-fails-files-nothing
  (let [calls (atom [])]
    (with-redefs [home/list-files (constantly [nfd-name])
                  home/supported-file-type? (constantly true)
                  home/importable? (constantly true)
                  home/ren (fn [from to] (swap! calls conj [:ren from to]) false)
                  home/move-file (fn [n] (swap! calls conj [:move n]))
                  file/ingest (fn [_ n _ _] (swap! calls conj [:ingest n]))
                  common/get-item-or-throw-error (constantly 1)]
      (batch/ingest nil "IMPORT" nil nil))
    (is (= [[:ren nfd-name (names/nfc nfd-name)]] @calls))))

(deftest the-traversal-guard-holds-in-every-form
  (on-utf-8-paths
    (spit (io/file *root* (str "J" "ü" "rgen.pdf")) "outside")
    (.mkdirs (io/file (folder :docs)))
    (doseq [asked ["../Jürgen.pdf" "../Jürgen.pdf"]]
      (is (nil? (names/resolve-in (folder :docs) asked)))
      (is (nil? (names/file-response asked {:root (folder :docs)}))))))

(deftest an-import-files-an-nfd-name-as-nfc
  ;; Disk-free: what batch/ingest does with the name, observed at the calls
  ;; that put it on disk (ren, move-file) and into the db (file/ingest).
  (let [calls (atom [])]
    (with-redefs [home/list-files (constantly [nfd-name "plain.pdf"])
                  home/supported-file-type? (constantly true)
                  home/importable? (constantly true)
                  home/ren (fn [from to] (swap! calls conj [:ren from to]))
                  home/move-file (fn [n] (swap! calls conj [:move n]))
                  file/ingest (fn [_ n _ _] (swap! calls conj [:ingest n]))
                  common/get-item-or-throw-error (constantly 1)]
      (batch/ingest nil "IMPORT" nil nil))
    (is (= [[:ren nfd-name (names/nfc nfd-name)]
            [:ingest (names/nfc nfd-name)]
            [:move (names/nfc nfd-name)]
            ;; already NFC: not renamed
            [:ingest "plain.pdf"]
            [:move "plain.pdf"]]
           @calls))))

(deftest the-path-encoding-check
  (testing "where it is UTF-8, nothing"
    (with-redefs [names/utf-8-paths? (constantly true)]
      (is (nil? (names/check-path-encoding! true)))))
  (testing "where it is not: refused in production, named fix in the message"
    (with-redefs [names/utf-8-paths? (constantly false)]
      (let [e (try (names/check-path-encoding! true) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (re-find #"LC_ALL=C\.UTF-8" (ex-message e))))
      (testing "only logged in dev"
        (is (nil? (names/check-path-encoding! false)))))))
