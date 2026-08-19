(ns datastore.schema-test
  "The part-of columns have to arrive on databases that already exist -- the
   human's dev and prod db both predate them -- so apply-schema! must add them
   in place, without touching the rows that are already there, and must stay
   safe to run on every boot."
  (:require [clojure.test :refer [deftest is testing]]
            [datastore.connection :as connection]
            [datastore.schema :as schema]
            [next.jdbc :as jdbc]))

(def ^:private pre-migration-relations
  "The relations table as it stood before this change: no is_part_of, no
   part_of_sort_idx."
  (str "CREATE TABLE relations ("
       "id INTEGER PRIMARY KEY AUTOINCREMENT,"
       "owner_id INTEGER NOT NULL,"
       "target_id INTEGER NOT NULL,"
       "show_badge INTEGER DEFAULT 1,"
       "annotation TEXT)"))

(defn- columns
  [db table]
  (into #{} (map #(or (:table_info/name %) (:name %)))
        (jdbc/execute! db [(str "PRAGMA table_info(" table ")")])))

(def ^:private pre-tombstone-history
  "The two history tables as they stood before deletion became a tombstoning:
   versions, and no column saying which of them a delete wrote."
  [(str "CREATE TABLE history (id INTEGER NOT NULL, text TEXT, title TEXT,"
        "version INTEGER NOT NULL,"
        "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, source TEXT,"
        "PRIMARY KEY (id, version))")
   (str "CREATE TABLE relation_history (owner_id INTEGER NOT NULL, target_id INTEGER NOT NULL,"
        "text TEXT, version INTEGER NOT NULL,"
        "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, source TEXT,"
        "PRIMARY KEY (owner_id, target_id, version))")])

(defn- with-legacy-db
  "Run f against a datasource holding a relations table in its pre-migration
   shape, with one row already in it, and both history tables in theirs."
  [f]
  (let [file (doto (java.io.File/createTempFile "rhizome-schema-test" ".db")
               (.deleteOnExit))
        db (connection/make-datasource {:dbname (.getAbsolutePath file)})]
    (try (jdbc/execute-one! db [pre-migration-relations])
         (jdbc/execute-one! db ["INSERT INTO relations (owner_id, target_id) VALUES (1, 2)"])
         (doseq [stmt pre-tombstone-history] (jdbc/execute-one! db [stmt]))
         (jdbc/execute-one! db [(str "INSERT INTO history (id, text, title, version, source) "
                                     "VALUES (1, 'an older description', 'Title', 1, 'app')")])
         (jdbc/execute-one! db [(str "INSERT INTO relation_history "
                                     "(owner_id, target_id, text, version, source) "
                                     "VALUES (1, 2, 'an older edge text', 1, 'app')")])
         (f db)
         (finally (.delete file)))))

(deftest part-of-columns-are-added-to-an-existing-relations-table
  (with-legacy-db
    (fn [db]
      (is (not (contains? (columns db "relations") "is_part_of"))
          "precondition: the legacy table does not have the column yet")
      (schema/apply-schema! db)
      (testing "both columns arrive"
        (is (contains? (columns db "relations") "is_part_of"))
        (is (contains? (columns db "relations") "part_of_sort_idx")))
      (testing "the row that was there before reads as not-part-of"
        (let [row (jdbc/execute-one! db ["SELECT * FROM relations WHERE owner_id = 1"])]
          (is (= 0 (:relations/is_part_of row)))
          (is (= -1 (:relations/part_of_sort_idx row)))))
      (testing "a relation written without them takes the defaults"
        (jdbc/execute-one! db ["INSERT INTO relations (owner_id, target_id) VALUES (3, 4)"])
        (let [row (jdbc/execute-one! db ["SELECT * FROM relations WHERE owner_id = 3"])]
          (is (= 0 (:relations/is_part_of row)))
          (is (= -1 (:relations/part_of_sort_idx row))))))))

(deftest a-comment-does-not-swallow-the-statements-after-it
  (testing "an apostrophe inside a -- comment must not open a string literal"
    (is (= ["CREATE TABLE a (x INTEGER)"
            "-- the table's second half\nCREATE TABLE b (y INTEGER)"]
           (schema/split-statements
             "CREATE TABLE a (x INTEGER);\n-- the table's second half\nCREATE TABLE b (y INTEGER);"))))
  (testing "and a semicolon inside one does not end the statement"
    (is (= ["CREATE TABLE a ( -- one; two\n  x INTEGER)"]
           (schema/split-statements "CREATE TABLE a ( -- one; two\n  x INTEGER);")))))

(deftest the-tombstone-column-is-added-to-existing-history-tables
  (with-legacy-db
    (fn [db]
      (testing "before"
        (is (not (contains? (columns db "history") "tombstone")))
        (is (not (contains? (columns db "relation_history") "tombstone"))))
      (schema/apply-schema! db)
      (testing "after"
        (is (contains? (columns db "history") "tombstone"))
        (is (contains? (columns db "relation_history") "tombstone")))
      (testing "and the versions already there read as what they are: not deletions"
        ;; They cannot be told apart after the fact -- the delete that would have
        ;; written one of them left nothing behind to recognise -- so every version
        ;; a database already holds is an ordinary superseded version, and 0 is the
        ;; honest answer rather than merely the convenient default.
        (is (= 0 (:history/tombstone
                   (jdbc/execute-one! db ["SELECT tombstone FROM history WHERE id = 1"]))))
        (is (= 0 (:relation_history/tombstone
                   (jdbc/execute-one!
                     db
                     ["SELECT tombstone FROM relation_history WHERE owner_id = 1"]))))
        (is (= "an older description"
               (:history/text (jdbc/execute-one! db ["SELECT text FROM history WHERE id = 1"])))
            "and the migration left the rows themselves alone")))))

(deftest applying-the-schema-twice-changes-nothing
  (with-legacy-db
    (fn [db]
      (schema/apply-schema! db)
      (jdbc/execute-one! db
                         [(str "UPDATE relations SET is_part_of = 1, part_of_sort_idx = 7 "
                               "WHERE owner_id = 1")])
      (schema/apply-schema! db)
      (is (= #{"id" "owner_id" "target_id" "show_badge" "annotation" "description"
               "description_source" "is_part_of" "part_of_sort_idx"}
             (columns db "relations"))
          "the second run adds no duplicate column")
      (let [row (jdbc/execute-one! db ["SELECT * FROM relations WHERE owner_id = 1"])]
        (is (= 1 (:relations/is_part_of row)) "and does not reset what was written")
        (is (= 7 (:relations/part_of_sort_idx row)))))))
