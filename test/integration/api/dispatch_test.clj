(ns api.dispatch-test
  "Envelope-level behaviour of the /api endpoint: things that are about
   the dispatcher itself, not about any one repository function."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]))

(deftest unknown-fn-test
  (with-fresh-db "an unknown function name surfaces as :thrown"
    (let [msg (try (call! :no-such-fn) ::no-throw
                   (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
      (is (string? msg))
      (is (re-find #"Unknown function" msg)))))

(deftest exception-from-handler-test
  (with-fresh-db "an exception thrown inside a dispatch fn surfaces as :thrown"
    (let [msg (try (call! :fetch-aggregated-contexts {}) ::no-throw
                   (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
      (is (string? msg))
      (is (re-find #"selected-item" msg)
          "fetch-aggregated-contexts requires :selected-item; the message bubbles up"))))

(deftest server-injects-db-test
  (with-fresh-db "the harness never passes :db; the server injects it from config"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})]
      (is (some? (:id ctx))
          "if :db were missing the insert would have thrown"))))

(deftest rich-clojure-types-roundtrip-test
  (with-fresh-db "keywords, sets, and nested maps survive the transit envelope"
    (let [resp (call! :list-resources
                       {:active-search :items
                        :selected-secondary-contexts #{1 2 3}
                        :nested {:k :v}})]
      (is (contains? resp :items))
      (is (not (contains? resp :contexts))
          ":active-search :items takes the items-only branch"))))
