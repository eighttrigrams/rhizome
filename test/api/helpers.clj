(ns api.helpers
  (:require [clojure.test :refer [testing]]
            [et.vp.ds.search-test :refer [reset-db with-time]]))

(defmacro with-fresh-db [description & body]
  `(testing ~description
     (reset-db)
     (with-time ~@body)))
