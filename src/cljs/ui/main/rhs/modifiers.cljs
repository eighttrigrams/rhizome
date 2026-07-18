(ns ui.main.rhs.modifiers
  (:require [reagent.core :as r]))

(defonce *alt-pressed? (r/atom false))

(defn indicator
  []
  (when @*alt-pressed?
    [:div#alt-indicator
     {:title "Option (Alt) held"
      :style {:position "fixed"
              :top "10px"
              :right "10px"
              :z-index 10000
              :width "14px"
              :height "14px"
              :border-radius "50%"
              :background "#2ecc71"
              :box-shadow "0 0 10px 3px rgba(46, 204, 113, 0.85)"
              :pointer-events "none"}}]))
