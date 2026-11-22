(ns et.rz.names
  "File names with umlauts: one name, two spellings, and a JVM that may not
   be able to spell either.

   ## NFC and NFD

   `ü` can be one character (NFC, U+00FC) or two (NFD, `u` and a combining
   diaeresis, U+0308). A Mac's APFS takes both for the same name. Linux
   compares bytes, so there they are two different names, and a lookup in the
   one form misses a file stored in the other.

   Rhizome is to run on Linux. The database stores names as NFC (all but two
   of the owner's, which are NFD). Files that came from a Mac may be NFD on
   disk; Syncthing, the planned copier, writes NFC on Linux. So:

   - every lookup of a name on disk goes through `resolve-in` or
     `file-response`, which try the name as NFC, then as NFD (then as it came,
     should it be neither, a mix);
   - a name newly filed (batch import, et.rz.hub.repository.insertion.batch)
     is made NFC first, on disk and in the database alike, so nothing new can
     drift.

   ## A JVM that cannot name the file at all

   On Linux the JVM turns a file name into bytes with `sun.jnu.encoding`,
   which it takes from the locale at start and nowhere else
   (`-Dsun.jnu.encoding=UTF-8` is ignored, measured on JDK 21). Under a POSIX
   locale (LANG unset, the default in a container) that is ASCII: every
   non-ASCII name becomes `?`, and `File.toPath` throws. So no form of the
   name is found, and NFC against NFD does not even come up.
   `check-path-encoding!` says so at start. On macOS it is always UTF-8."
  (:require [cambium.core :as log]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.util.response :as response])
  (:import (java.io File)
           (java.nio.file InvalidPathException)
           (java.text Normalizer Normalizer$Form)))

(defn nfc [^String s] (Normalizer/normalize s Normalizer$Form/NFC))

(defn nfd [^String s] (Normalizer/normalize s Normalizer$Form/NFD))

(defn forms
  "The spellings of `name` to try, in order: NFC, NFD, then `name` itself if
   it is neither. One entry for a name without anything to compose."
  [name]
  (distinct [(nfc name) (nfd name) name]))

(defn- inside?
  [^File root ^File f]
  (str/starts-with? (.getCanonicalPath f) (str (.getCanonicalPath root) File/separator)))

(defn path-in
  "The File `name` names inside the folder `root`, whether or not it exists,
   or nil when it is a path rather than a name (a separator in it), would lie
   outside the folder (by canonical path: a symlink out), or the JVM cannot
   write the name in its path
   encoding. java.io.File's own constructor, not io/file: that one throws on
   an absolute name, which reached a request as a 500."
  ^File [root ^String name]
  ;; A name, not a path: the folders are flat, and a name with a separator in
  ;; it ("../x", "/etc/x", which File would join *under* root) is no file
  ;; of theirs. The canonical check below stays, for a symlink out.
  (when-not (or (str/includes? name "/") (str/includes? name File/separator) (str/includes? name "\u0000"))
    (let [root (io/file root)
          f (File. ^File root name)]
      (try
        (.toPath f)
        (when (inside? root f) f)
        (catch InvalidPathException _ nil)))))

(defn resolve-in
  "The file called `name` inside the folder `root`, in whichever of its forms
   it is on disk, or nil. Never one outside `root` (by canonical path)."
  [root name]
  (some (fn [form]
          (when-let [f (path-in root form)]
            (when (.isFile f) f)))
        (forms name)))

(defn file-response
  "ring.util.response/file-response, tried for each form of `path`. Ring's own
   :root guard against traversal stays in force for every one."
  [path opts]
  (some (fn [form]
          (try (response/file-response form opts)
               (catch InvalidPathException _ nil)))
        (forms path)))

(def ^:private fix
  "LC_ALL=C.UTF-8 (or any UTF-8 locale) in the environment of the java process")

(defn path-encoding
  "The encoding this JVM writes file names in."
  []
  (System/getProperty "sun.jnu.encoding"))

(defn utf-8-paths?
  []
  (= "UTF-8" (path-encoding)))

(defn check-path-encoding!
  "At start: refuse to run (`refuse?`), or else log an error that cannot be
   missed, when this JVM cannot name non-ASCII files. Refused in production,
   where the files are; only logged in dev, test and e2e, so that a box whose
   start cannot set a locale still runs."
  [refuse?]
  (when-not (utf-8-paths?)
    (let [msg (str "This JVM cannot open files whose names are not plain ASCII (umlauts and the like): "
                   "sun.jnu.encoding is " (path-encoding)
                   ", because the locale is not UTF-8. Every such file will be Not Found. Fix: " fix ".")]
      (log/error msg)
      (when refuse?
        (throw (ex-info (str "Refusing to start. " msg) {:sun.jnu.encoding (path-encoding)}))))))
