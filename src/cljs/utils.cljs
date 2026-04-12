(ns utils
  (:require [goog.async.Debouncer]
            [clojure.string :as str]))

(defn debounce
  [f interval]
  (let [dbnc (goog.async.Debouncer. f interval)]
    (fn [& args] (.apply (.-fire dbnc) dbnc (to-array args)))))

(def ^:private roman-offset 1000001)

(def ^:private roman-vals
  [[1000 "m"] [900 "cm"] [500 "d"] [400 "cd"]
   [100 "c"] [90 "xc"] [50 "l"] [40 "xl"]
   [10 "x"] [9 "ix"] [5 "v"] [4 "iv"] [1 "i"]])

(defn int->roman
  [n]
  (loop [n n parts []]
    (if (zero? n)
      (apply str parts)
      (let [[val s] (first (filter #(<= (first %) n) roman-vals))]
        (recur (- n val) (conj parts s))))))

(def ^:private roman-char-vals
  {\i 1 \v 5 \x 10 \l 50 \c 100 \d 500 \m 1000})

(defn roman->int
  [s]
  (let [s (str/lower-case s)
        vals (map roman-char-vals s)]
    (when (every? some? vals)
      (reduce + (map-indexed
                  (fn [i v]
                    (if (and (< i (dec (count vals)))
                             (< v (nth vals (inc i))))
                      (- v) v))
                  vals)))))

(defn roman?
  [s]
  (re-matches #"^[ivxlcdmIVXLCDM]+$" s))

(defn sort-idx->display
  [sort-idx]
  (cond
    (= sort-idx -1) ""
    (< sort-idx -1) (int->roman (+ sort-idx roman-offset))
    :else (str sort-idx)))

(defn display->sort-idx
  [s]
  (let [s (str/trim s)]
    (cond
      (empty? s) -1
      (roman? s) (- (roman->int s) roman-offset)
      :else (js/parseInt s 10))))
