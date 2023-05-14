(ns ui.main.input
  (:require [reagent.core :as r]
            [ui.actions :as actions]))

(defn input-component [*state]
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "search-input"))
    :render (fn []
              [:input#search-input
               {:autoComplete :off
                :on-change    #(actions/search! *state (.-value (.-target %)))
                :on-key-down  #(let [code (.-code %)]
                                 (.stopPropagation %)
                                 (when (= code "Enter")
                                   (actions/select-first-context! *state))
                                 (when (= code "Escape")
                                   (actions/quit-search! *state)))}])}))

(defn component [*state]
  [:<>
   [:div.active-search-input-container [input-component *state]]
   [:div.mask.search-active
    {:on-click #(actions/quit-search! *state)}]])
