(ns et.rz.hub.repository.insertion.unique
  "The name a file from imports is filed under when its own is taken.

   The owner: \"import the second as (2)\". A name is taken when, in either
   Unicode form,
   - a file of that name lies in any media folder (homefolder/
     validate-not-exists, the check file/ingest begins with);
   - an item already has it as its file or its image (what file/ingest
     refuses, by the same query);
   - another file in imports has it -- the rename to it would be refused.
   The first free of `name`, `name (2)`, `name (3)`... is the name the file is
   renamed to in imports, and so the name on disk and in the db (et.rz.names/
   first-free). Nothing existing is renamed or overwritten."
  (:require [clojure.java.io :as io]
            [et.rz.hub.ds :as datastore]
            [et.rz.hub.repository.homefolder :as home]
            [et.rz.names :as names])
  (:import (java.io File)
           (java.nio.file Files)))

(defn- on-disk? [name]
  (try (home/validate-not-exists name) false
       (catch Exception _ true)))

(defn- in-db? [db name]
  (boolean
    (some (fn [form]
            (some #(:id (datastore/get-item-by-path db (str "data->'resource-links'->>'" % "'") form))
                  ["file" "image"]))
          (names/forms name))))

(defn- other-in-imports?
  "Another file than `own` in `imports-dir` called `name`, in either form."
  [imports-dir ^File own name]
  (when-let [f (names/resolve-in imports-dir name)]
    (not (Files/isSameFile (.toPath own) (.toPath ^File f)))))

(defn taken?
  [db imports-dir own-name name]
  (or (on-disk? name)
      (other-in-imports? imports-dir (io/file imports-dir own-name) name)
      (in-db? db name)))

(defn free-name
  "The name the file `own-name` in `imports-dir`, to be filed as `name`, is
   filed under: `name` itself, or the first free numbered one."
  [db imports-dir own-name name]
  (names/first-free #(taken? db imports-dir own-name %) name))
