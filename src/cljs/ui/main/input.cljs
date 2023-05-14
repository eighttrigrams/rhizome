(ns ui.main.input
  (:require [reagent.core :as r]
            [ui.actions :as actions]))

(defn input-component [*state]
  (r/create-class
   {:component-did-mount #(.focus (.getElementById js/document "search-input"))
    :render (fn []
              [:input#search-input
               {:autoComplete :off
                :on-change    #(do (swap! *state assoc :q (.-value (.-target %)))
                                   (actions/search! *state))
                :on-key-down  #(let [code (.-code %)]
                                 (.stopPropagation %)
                                 (when (and (= code "Enter")
                                            (= :contexts (:active-search @*state)))
                                   (actions/select-first-context! *state))
                                 (when (= code "Escape")
                                   (actions/quit-search! *state)))}])}))

(defn component [*state]
  [:<>
   [:div.active-search-input-container [input-component *state]]
   (when (not (and (nil? (:selected-issue @*state))
                   (:selected-context @*state)
                   (= :issues (:active-search @*state))
                   (not (:search-globally? @*state))))
     [:div.mask.search-active
      {:on-click #(actions/quit-search! *state)}])])
