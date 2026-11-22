(ns et.rz.hub.repository.insertion.arrival
  "Which files in imports are ready to be filed, and which are not a file of
   their kind at all.

   Imports are to be fed by Syncthing from four Macs, and a file can be there
   before it is whole: under a temporary name, or under its own name while it
   is still being written. Before, the batch import skipped Syncthing's
   temporaries only by the accident of their suffix (`.syncthing.x.png.tmp`
   has no suffix import knows). Now on purpose:

   - **still arriving** (`arriving?`, `unsettled`): Syncthing's and browsers'
     temporaries, dotfiles, and any file whose size or modification time moved
     in the last few seconds. Skipped quietly -- the next import takes it.
   - **not what its suffix says** (`content-problem`): empty, or first bytes
     that are not that kind of file (a half-copied or misnamed one). Left in
     imports, and named in the log with the reason, so nothing becomes an item
     that cannot be shown.

   Names that need repairing are et.rz.names/repair's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

;; ## Still arriving

(def ^:private temporary
  "Names that are a file on its way, not a file: Syncthing writes into
   `.syncthing.<name>.tmp` (and `~syncthing~<name>.tmp` on Windows-made
   setups), browsers into .part / .crdownload / .download, and a dotfile is
   either one of those or a Mac's own (.DS_Store, ._ resource forks)."
  [#"^\." #"^~syncthing~" #"(?i)\.(tmp|part|crdownload|download)$"])

(defn arriving?
  "Whether `file-name`, by its name alone, is a file still on its way."
  [file-name]
  (boolean (some #(re-find % file-name) temporary)))

(def ^:dynamic *settle-ms*
  "How long a file must have been left alone to count as whole: its
   modification time at least this far back, and its size and time unchanged
   over a look of `*look-ms*`."
  5000)

(def ^:dynamic *look-ms*
  "The one pause per import run in which sizes are watched for change."
  1000)

(defn- stamp [^File f] [(.length f) (.lastModified f)])

(defn unsettled
  "The names among `file-names` in `dir` that are still being written: touched
   within *settle-ms*, or grown or touched during one look of *look-ms*. One
   look for all of them, so an import waits a second, not a second per file.
   No look at all when every file is old enough already -- the usual case."
  [dir file-names]
  (let [files (into {} (map (fn [n] [n (io/file dir n)])) file-names)
        now (System/currentTimeMillis)
        recent (set (filter #(> (.lastModified ^File (files %)) (- now *settle-ms*)) file-names))
        before (into {} (map (fn [[n f]] [n (stamp f)])) files)]
    (if (seq recent)
      (do (Thread/sleep (long *look-ms*))
          (into recent (filter #(not= (before %) (stamp (files %)))) file-names))
      recent)))

;; ## What the suffix promises

(defn- ascii [^bytes head from to]
  (when (>= (alength head) to)
    (String. head (int from) (int (- to from)) "ISO-8859-1")))

(defn- u8 [^bytes head i] (when (> (alength head) i) (bit-and 0xff (aget head i))))

(def ^:private iso-media-atoms
  "What an mp4, m4a or mov may begin with at byte 4: `ftyp` for nearly all,
   and the first atom itself for older QuickTime files that have none."
  #{"ftyp" "moov" "mdat" "wide" "free" "skip" "pnot"})

(def ^:private kinds
  "Suffix -> [what it should be, a test of the first bytes]."
  (let [jpeg ["a JPEG" #(= [0xff 0xd8 0xff] [(u8 % 0) (u8 % 1) (u8 % 2)])]
        iso-media ["an MP4/QuickTime file" #(iso-media-atoms (ascii % 4 8))]]
    {"jpg" jpeg
     "jpeg" jpeg
     "png" ["a PNG" #(= "\u0089PNG\r\n\u001a\n" (ascii % 0 8))]
     "webp" ["a WebP" #(and (= "RIFF" (ascii % 0 4)) (= "WEBP" (ascii % 8 12)))]
     ;; Readers accept the header anywhere in the first 1024 bytes.
     "pdf" ["a PDF" #(some-> (ascii % 0 (min 1024 (alength ^bytes %))) (str/includes? "%PDF-"))]
     "mp4" iso-media
     "mov" iso-media
     "m4a" iso-media
     ;; An ID3 tag, or straight into an MPEG audio frame (11 bits of sync).
     "mp3" ["an MP3" #(or (= "ID3" (ascii % 0 3))
                          (and (= 0xff (u8 % 0)) (some-> (u8 % 1) (bit-and 0xe0) (= 0xe0))))]
     "wav" ["a WAV" #(and (= "RIFF" (ascii % 0 4)) (= "WAVE" (ascii % 8 12)))]
     "ogg" ["an Ogg file" #(= "OggS" (ascii % 0 4))]
     "flv" ["an FLV" #(= "FLV" (ascii % 0 3))]
     "tiff" ["a TIFF" #(#{"II*\u0000" "MM\u0000*"} (ascii % 0 4))]}))

(defn- head [^File f]
  (with-open [in (io/input-stream f)]
    (let [buf (byte-array 1024)
          n (.read in buf)]
      (if (pos? n) (java.util.Arrays/copyOf buf n) (byte-array 0)))))

(defn content-problem
  "Why the file `f`, called `file-name`, is not what its suffix says, or nil
   when it is (or its suffix is not one import checks)."
  [^File f file-name]
  (let [suffix (some-> (str/last-index-of file-name ".") inc (#(subs file-name %)) str/lower-case)]
    (when-let [[what ok?] (kinds suffix)]
      (cond
        (zero? (.length f)) "it is empty"
        (not (ok? (head f))) (str "its first bytes are not those of " what)))))
