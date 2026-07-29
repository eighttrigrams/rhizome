(ns config-test
  "The primary/replica role decision. Pure function plus the filesystem lookup
   feeding it, so both halves are testable without a prod-mode process."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config])
  (:import [java.io File]))

(deftest read-only-replica-decision-test
  (testing "prod mode without the marker is the only read-only-replica case"
    (is (true? (config/read-only-replica? {:dev? false} false))
        "prod + no marker -> read-only replica")
    (is (false? (config/read-only-replica? {:dev? false} true))
        "prod + marker -> primary, writes enabled")
    (is (false? (config/read-only-replica? {:dev? true} false))
        "dev needs no marker")
    (is (false? (config/read-only-replica? {:dev? true} true))
        "dev is unaffected by a marker being there")))

(deftest primary-marker-present-test
  (testing "the marker is a plain file lookup in the start directory"
    (let [f (File/createTempFile "primary" ".nosync")]
      (try
        (is (true? (config/primary-marker-present? (.getPath f))))
        (finally (.delete f)))
      (is (false? (config/primary-marker-present? (.getPath f)))))))

(deftest this-process-booted-as-primary-test
  (testing "the test config (dev mode, no marker) carries the decision as :read-only-replica?"
    (is (false? (:read-only-replica? config/config))))
  (testing "the marker name is the one the owner's sync excludes"
    (is (= "primary.nosync" config/primary-marker))))
