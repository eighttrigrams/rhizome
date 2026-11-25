(ns et.rz.hub.repository.insertion.arrival
  "Which files in imports are ready to be filed, and which are not a file of
   their kind at all.

   Imports are to be fed by Syncthing from four Macs, and a file can be there
   before it is whole: under a temporary name, or under its own name while it
   is still being written. Before, the batch import skipped Syncthing's
   temporaries only by the accident of their suffix (`.syncthing.x.png.tmp`
   has no suffix import knows). Now on purpose:

   - **still arriving** (`arriving?`, `unsettled`): Syncthing's and browsers'
     temporaries, dotfiles, and any file whose size or modification time moves
     while the import looks. Skipped, with the reason logged at info -- the
     next import takes it.
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

(def ^:dynamic *look-ms*
  "The one pause per import run in which every file is watched for change."
  1000)

(defn- stamp [^File f] [(.length f) (.lastModified f)])

(defn unsettled
  "The names among `file-names` in `dir` that are still being written: their
   size or modification time changed during one look of *look-ms*. One look
   for all of them, so an import waits a second, not a second per file.

   By the look alone, not by how long ago the file was touched: Syncthing
   keeps the source's modification time, and a clock wrong on another Mac
   would leave a file dated in the future 'just written' until that date --
   not imported, and nothing said (second review, finding 2)."
  [dir file-names]
  (if (empty? file-names)
    #{}
    (let [files (into {} (map (fn [n] [n (io/file dir n)])) file-names)
          before (into {} (map (fn [[n f]] [n (stamp f)])) files)]
      (Thread/sleep (long *look-ms*))
      (set (filter #(not= (before %) (stamp (files %))) file-names)))))

;; ## What the suffix promises

(defn- ascii [^bytes head from to]
  (when (>= (alength head) to)
    (String. head (int from) (int (- to from)) "ISO-8859-1")))

(defn- u8 [^bytes head i] (when (> (alength head) i) (bit-and 0xff (aget head i))))

(def ^:private iso-media-atoms
  "What an mp4, m4a or mov may begin with at byte 4: `ftyp` for nearly all,
   and the first atom itself for older QuickTime files that have none."
  #{"ftyp" "moov" "mdat" "wide" "free" "skip" "pnot"})

(defn- picture?
  "A picture the browser draws, whatever its suffix says: a .jpg that is
   really a PNG shows all the same (second review, finding 5). GIF counts too,
   though import files no .gif: it is what a misnamed .png sometimes is."
  [head]
  (or (= [0xff 0xd8 0xff] [(u8 head 0) (u8 head 1) (u8 head 2)])
      (= "\u0089PNG\r\n\u001a\n" (ascii head 0 8))
      (and (= "RIFF" (ascii head 0 4)) (= "WEBP" (ascii head 8 12)))
      (#{"GIF87a" "GIF89a"} (ascii head 0 6))))

(def ^:private mp3-window
  "How far into the file the first MPEG frame may start: encoders and taggers
   leave zero padding or junk in front of it."
  4096)

(defn- mp3?
  "An ID3 tag at the start, or an MPEG audio frame sync (eleven bits set)
   anywhere in the first mp3-window bytes."
  [^bytes head]
  (or (= "ID3" (ascii head 0 3))
      (let [n (min mp3-window (alength head))]
        (some (fn [i] (and (= 0xff (u8 head i))
                           (= 0xe0 (bit-and 0xe0 (u8 head (inc i))))))
              (range 0 (dec n))))))

(def ^:private kinds
  "Suffix -> [what it should be, a test of the first bytes]."
  (let [picture ["a picture (JPEG, PNG, WebP or GIF)" picture?]
        iso-media ["an MP4/QuickTime file" #(iso-media-atoms (ascii % 4 8))]]
    {"jpg" picture
     "jpeg" picture
     "png" picture
     "webp" picture
     ;; Readers accept the header anywhere in the first 1024 bytes.
     "pdf" ["a PDF" #(some-> (ascii % 0 (min 1024 (alength ^bytes %))) (str/includes? "%PDF-"))]
     "mp4" iso-media
     "mov" iso-media
     "m4a" iso-media
     "mp3" ["an MP3" mp3?]
     ;; RF64 and BW64: the 64-bit WAVs, for files over 4 GB (broadcast audio).
     "wav" ["a WAV" #(and (#{"RIFF" "RF64" "BW64"} (ascii % 0 4)) (= "WAVE" (ascii % 8 12)))]
     "ogg" ["an Ogg file" #(= "OggS" (ascii % 0 4))]
     "flv" ["an FLV" #(= "FLV" (ascii % 0 3))]
     ;; Matroska: the EBML header's id, 1A 45 DF A3.
     "mkv" ["a Matroska file" #(= [0x1a 0x45 0xdf 0xa3] [(u8 % 0) (u8 % 1) (u8 % 2) (u8 % 3)])]
     ;; II*/MM* classic TIFF, II+/MM+ BigTIFF.
     "tiff" ["a TIFF" #(#{"II*\u0000" "MM\u0000*" "II+\u0000" "MM\u0000+"} (ascii % 0 4))]}))

(defn- head
  "The first bytes of `f`, as many as any check looks at."
  [^File f]
  (with-open [in (io/input-stream f)]
    (.readNBytes in (int mp3-window))))

(defn content-problem
  "Why the file `f`, called `file-name`, is not what its suffix says, or nil
   when it is (or its suffix is not one import checks)."
  [^File f file-name]
  (let [suffix (some-> (str/last-index-of file-name ".") inc (#(subs file-name %)) str/lower-case)]
    (when-let [[what ok?] (kinds suffix)]
      (cond
        (zero? (.length f)) "it is empty"
        (not (ok? (head f))) (str "its first bytes are not those of " what)))))
