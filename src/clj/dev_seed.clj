(ns dev-seed
  "Auto-seeding on startup, keyed off a completely empty items table (a
   \"fresh\" db, in prod or dev). The canonical contexts -- including the
   file-type contexts' named ids -- are app structure and seed in both prod
   and dev; the demo articles that used to be installed by `make onboard`
   are dev-only. Opt out with `:skip-seed? true` in config.edn.

   This replaces the old babashka seed scripts so the dev experience is
   `make start` + nothing else."
  (:require [cambium.core :as log]
            [clojure.edn :as edn]
            [db :as db]
            [et.vp.ds.relations :as relations]
            [repository.insertion.file :as file]))

(def ^:private contexts
  ["Imports"
   "Files" "Documents" "Audio" "Video" "Image"
   "MP3s" "OGGs" "M4As" "WAVs" "MP4s" "FLVs" "MOVs"
   "PDFs" "TIFFs" "JPEGs" "PNGs" "WEBPs"
   "YouTube" "Substack" "GitHub" "Apple Podcasts" "Twitter"
   "YouTube Videos" "YouTube Channels" "Substacks" "Articles"
   "Podcast Episodes" "Podcasts" "GitHub Repo" "GitHub User"
   "Twitter Handles" "Poasts" "Library"
   "2020" "2021" "2022" "2023" "2024" "2025"])

(def ^:private demo-articles-path "./scripts/demo-articles.edn")

(defn items-empty? [db]
  (zero? (-> (db/execute-one! db ["SELECT count(*) AS n FROM items"])
             :n)))

(defn- insert-context! [db title]
  ;; File-type contexts get their stable named id (human_readable_id) so file
  ;; ingestion can match them by id rather than title. Other contexts get NULL
  ;; (the unique index is partial, so NULLs don't collide).
  (db/execute-one!
   db
   ["INSERT INTO items
       (title, short_title, data, is_context, human_readable_id, inserted_at, updated_at, updated_at_ctx)
     VALUES (?, '', '{}', 1, ?, datetime('now'), datetime('now'), datetime('now'))"
    title (get file/file-contexts title)]))

(defn- articles-context-id [db]
  (-> (db/execute-one!
       db
       ["SELECT id FROM items WHERE is_context=1 AND title='Articles' LIMIT 1"])
      :items/id))

(defn- insert-article! [db {:keys [title description]}]
  (-> (db/execute-one!
       db
       ["INSERT INTO items
           (title, short_title, description, data, is_context, inserted_at, updated_at, updated_at_ctx)
         VALUES (?, '', ?, '{}', 0, datetime('now'), datetime('now'), datetime('now'))
         RETURNING id"
        title (or description "")])
      :items/id))

(defn- link! [db owner-id target-id]
  (db/execute-one!
   db
   ["INSERT INTO relations (owner_id, target_id, show_badge) VALUES (?, ?, 1)"
    owner-id target-id])
  ;; The row is half a relation. Everything that reads one reads the `contexts`
  ;; mirror inside items.data instead -- the badges under a card, the related
  ;; contexts in the item's edit modal, the aggregated contexts -- so a row
  ;; seeded without its entry describes a relation nothing in the UI can see:
  ;; the demo articles sat under Articles wearing no badge, and their edit modal
  ;; listed no related context at all.
  ;;
  ;; Built out of the table rather than written by hand here, so the seed cannot
  ;; say a relation differently from the way every other write channel says it.
  (relations/set-collection-titles-of-new-item db target-id))

(defn- seed-contexts! [db]
  (log/info (str "Seeding " (count contexts) " contexts"))
  (doseq [title contexts]
    (insert-context! db title)))

(defn- seed-articles! [db]
  (when-let [articles (try (edn/read-string (slurp demo-articles-path))
                           (catch Exception _ nil))]
    (let [ctx-id (articles-context-id db)]
      (log/info (str "Seeding " (count articles) " demo articles into 'Articles' (id " ctx-id ")"))
      (doseq [a articles]
        (link! db ctx-id (insert-article! db a))))))

(defn maybe-seed!
  "Seed an empty db. The canonical contexts (incl. the file-type contexts'
   named ids) are app structure, so they seed in prod AND dev; the demo
   articles are dev-only. Returns :seeded, :skipped (per config), :e2e
   (never seeds e2e), :not-empty, or :error. Never throws -- a seed failure
   logs and the server still comes up.

   :e2e? mode also implies :dev? true (see config.clj), but the e2e
   suite is built around an empty db, so we never seed it regardless
   of :skip-seed?."
  [{:keys [db dev? e2e? skip-seed?]}]
  (try
    (cond
      e2e?                    :e2e
      skip-seed?              (do (log/info "dev-seed: skipped (:skip-seed? true)") :skipped)
      (not (items-empty? db)) :not-empty
      :else (do (seed-contexts! db)
                (when dev? (seed-articles! db))
                (log/info "dev-seed: done")
                :seeded))
    (catch Exception e
      (log/warn (str "dev-seed: failed -- " (.getMessage e)))
      :error)))
