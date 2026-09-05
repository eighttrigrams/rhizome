(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "server.jar")

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  ;; Both mains, one jar. The db-server is a second process out of the same
  ;; artifact -- `java -cp server.jar clojure.main -m db-server` -- so deploy
  ;; does not change shape when the database moves behind it.
  (b/compile-clj {:basis @basis
                  :ns-compile '[server db-server]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'server}))