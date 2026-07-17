(ns ui.main
  (:require [ui.actions :as actions]
            [ui.main.lhs :as lhs]
            [ui.main.rhs :as rhs]
            [ui.main.config :as config]
            [ui.main.vector-threshold-slider :as vector-threshold-slider]))

(defn component
  [*state]
  (actions/fetch! *state)
  (fn [*state]
    (if (:config-page? @*state)
      [config/component *state]
      [:div#sides-container
       [vector-threshold-slider/component *state]
       [:div#lhs-component.side-component [lhs/component *state]]
       [:div#rhs-component.side-component [rhs/component *state]]])))
