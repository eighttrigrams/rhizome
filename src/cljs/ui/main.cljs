(ns ui.main
  (:require [ui.actions :as actions]
            [ui.main.lhs :as lhs]
            [ui.main.rhs :as rhs]))

(defn component [*state]
  (actions/fetch! *state)
  (fn [*state]
    [:div#sides-container
     [:div#lhs-component.side-component [lhs/component *state]]
     [:div#rhs-component.side-component [rhs/component *state]]]))
