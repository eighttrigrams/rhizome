(ns repository.insertion.file
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.set :as set]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [utils :refer [condx]]
            [repository.insertion.common :as common]
            [repository.homefolder :as home]))

;; Single source of truth for the file-type contexts. Each is seeded with a
;; stable human-readable-id ("named id"); file ingestion and the startup
;; check below match contexts by these named ids rather than by the (mutable,
;; user-renamable) title.
(def file-contexts
  "title -> human-readable-id for every context a file can be filed under."
  {"Files"     "files"
   "Documents" "docs"
   "Audio"     "audio"
   "Video"     "video"
   "Image"     "images"
   "MP3s"      "mp3"
   "OGGs"      "ogg"
   "M4As"      "m4a"
   "WAVs"      "wav"
   "MP4s"      "mp4"
   "FLVs"      "flv"
   "MOVs"      "mov"
   "PDFs"      "pdf"
   "TIFFs"     "tiff"
   "JPEGs"     "jpg"
   "PNGs"      "png"
   "WEBPs"     "webp"})

;; TODO make condx work to match multiple cases, like case
;; when adding files, also see homefolder.clj (this here is 1 of 3 places)
(defn- classify
  "The human-readable-ids of the contexts a file with this title belongs to."
  [title]
  (condx #(str/ends-with? (str/lower-case title) %)
         "mp3"  ["mp3" "audio"]
         "ogg"  ["ogg" "audio"]
         "m4a"  ["m4a" "audio"]
         "wav"  ["wav" "audio"]
         "mp4"  ["mp4" "video"]
         "flv"  ["flv" "video"]
         "mov"  ["mov" "video"]
         "pdf"  ["pdf"]
         "tiff" ["tiff" "docs"]
         "jpg"  ["jpg" "images"]
         "jpeg" ["jpg" "images"]
         "png"  ["png" "images"]
         "webp" ["webp" "images"]))

(defn- ids-by-named-id
  "Map of human-readable-id -> item id for the contexts carrying `named-ids`."
  [db named-ids]
  (->> (search/find-items-by-ids db {:human-readable-ids named-ids})
       (keep (fn [{:keys [id human_readable_id]}]
               (when human_readable_id [human_readable_id id])))
       (into {})))

(defn missing-contexts
  "Named ids of the file-type contexts that are absent from the db."
  [db]
  (let [present (set (keys (ids-by-named-id db (vals file-contexts))))]
    (remove present (vals file-contexts))))

(defn ensure-contexts!
  "Hard startup gate: every file-type context must carry its named id, or we
   refuse to come up. A missing context means files of that type can't be
   filed and get silently dropped on import, so we'd rather not start at all.
   Callers exempt a completely empty db (it'll be seeded, or is e2e's
   intentionally-empty db)."
  [db]
  (let [missing (missing-contexts db)]
    (when (seq missing)
      (doseq [named-id missing]
        (log/error (str "Missing file-type context with named id '" named-id "'.")))
      (throw (ex-info (str "Refusing to start: missing file-type contexts (named ids): "
                           (str/join ", " missing))
                      {:missing missing})))))

(defn strip-suffix [title] (subs title 0 (str/last-index-of title ".")))

(defn- validate-not-exists
  [db file-name]
  (home/validate-not-exists file-name)
  (when (:id (datastore/get-item-by-path db "data->'resource-links'->>'file'" file-name))
    (throw (Exception. "file already exists!")))
  (when (:id (datastore/get-item-by-path db "data->'resource-links'->>'image'" file-name))
    (throw (Exception. "image already exists!"))))

(defn match? [title] (home/supported-file-type? title))

(defn ingest
  [db file-name context-ids-set _]
  (validate-not-exists db file-name)
  (when (re-find #"," file-name)
    (throw (Exception. (str "file name shouldn't contain commas: " file-name))))
  (let [named-ids (conj (classify file-name) "files")
        ids       (ids-by-named-id db named-ids)
        missing   (remove (set (keys ids)) named-ids)]
    (when (seq missing)
      (let [msg (str "Skipping " file-name " — no context for named id(s): "
                     (str/join ", " missing))]
        (log/warn msg)
        ;; Throwing aborts the upload (the caller won't move the file out of
        ;; imports) and is logged by the batch ingester.
        (throw (Exception. msg))))
    (let [context-ids-set (set/union context-ids-set (set (vals ids)))
          resource-links  (merge {:file file-name}
                                 (when (some #{"images"} named-ids) {:image file-name}))]
      (common/insert-item db (strip-suffix file-name) "" context-ids-set resource-links))))
