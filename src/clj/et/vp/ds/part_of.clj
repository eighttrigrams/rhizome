(ns et.vp.ds.part-of
  "The part-of edges read as a graph, and the one invariant kept over it.

   A part-of edge runs from the whole (the relation's owner) to the part (its
   target). A node may be part of several wholes -- this is a directed acyclic
   graph, not a tree, and nothing here assumes a unique parent or a unique path
   to a root. What it does not tolerate is a cycle: a thing cannot be part of
   itself, however long the way round.

   The check lives here, below both write channels, because the guarantee is
   about the database rather than about one client. Plain relations are not
   constrained -- they may go on forming cycles exactly as they always have."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn- edges
  "The part-of edge set as {whole-id #{part-id …}}, leaving out every edge into
   `replaced-part-id` -- the caller is about to delete and rewrite exactly those,
   so the graph to judge is the one without them."
  [db replaced-part-id]
  (reduce (fn [acc {:relations/keys [owner_id target_id]}]
            (update acc owner_id (fnil conj #{}) target_id))
    {}
    (jdbc/execute! db
                   ["SELECT owner_id, target_id FROM relations
                     WHERE is_part_of = 1 AND target_id <> ?"
                    replaced-part-id])))

(defn- path-down
  "A path from `from` down to `to` along the part-of edges: a vector of ids
   starting at `from` and ending at `to`, or nil when `to` is not below `from`.
   Breadth-first, so the path reported is a shortest one."
  [edges from to]
  (loop [queue [[from]]
         seen #{from}]
    (when-let [path (first queue)]
      (let [below (remove seen (get edges (peek path)))]
        (if-let [hit (some #{to} below)]
          (conj path hit)
          (recur (into (subvec queue 1) (map #(conj path %)) below)
                 (into seen below)))))))

(defn cycle-closing-path
  "The loop that making `part-id` a part of every id in `whole-ids` would close,
   as a vector of ids that begins and ends at the same id; nil while the part-of
   edges stay acyclic. Wholes are visited in id order so the answer to a given
   write does not depend on map ordering."
  [db part-id whole-ids]
  (when (seq whole-ids)
    (let [g (edges db part-id)]
      (some (fn [whole-id]
              (if (= whole-id part-id)
                [part-id part-id]
                (when-let [down (path-down g part-id whole-id)]
                  (into [whole-id] down))))
            (sort whole-ids)))))

(def ^:private max-name-length
  "Titles here are whole scraped headlines often enough that the untrimmed path
   reads as a wall rather than as a diagnosis. The id is what identifies the
   item anyway; the title is there to be recognised."
  60)

(defn- name-of
  [{:keys [title short_title id]}]
  (let [n (or (not-empty short_title) (not-empty title) "untitled")]
    (str (if (> (count n) max-name-length)
           (str (str/trimr (subs n 0 max-name-length)) "…")
           n)
         " (" id ")")))

(defn- describe
  "The loop written out, so the refusal says which way round the loop goes
   rather than only that there is one."
  [db path]
  (let [titles (into {}
                     (map (fn [{:items/keys [id title short_title]}]
                            [id {:id id :title title :short_title short_title}]))
                     (jdbc/execute! db
                                    (into [(str "SELECT id, title, short_title FROM items WHERE id IN ("
                                                (str/join "," (repeat (count (set path)) "?"))
                                                ")")]
                                          (sort (set path)))))]
    (str/join " → " (map #(name-of (get titles % {:id %})) path))))

(defn check-acyclic!
  "Throw when making `part-id` a part of every id in `whole-ids` would close a
   loop. The exception carries the path both in its message and, as ids, in its
   ex-data, so a caller can name it to whoever attempted the write."
  [db part-id whole-ids]
  (when-let [path (cycle-closing-path db part-id whole-ids)]
    (throw (ex-info (str "Refused: this would make a thing part of itself — "
                         (describe db path))
                    {:type ::cycle :path path}))))

(defn cycle-refusal
  "The refusal message out of an exception raised by check-acyclic!, or nil when
   the exception was something else and must go on being thrown."
  [e]
  (when (= ::cycle (:type (ex-data e)))
    (ex-message e)))
