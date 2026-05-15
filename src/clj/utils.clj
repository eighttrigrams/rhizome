(ns utils)

(defn condx [p & pairs] (first (keep (fn [[v f]] (when (p v) f)) (partition 2 pairs))))
