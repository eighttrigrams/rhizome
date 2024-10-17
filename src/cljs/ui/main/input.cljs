(ns ui.main.input
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.actions :as actions]
            [ui.main.input.key-handler :as key-handler]
            utils))

(defn save-input! [[*state evt]]
  (swap! *state assoc :q (.-value (or (.-target evt) evt)))
  (actions/search! *state))

(def save-input-debounced!
  (utils/debounce save-input! 180))

(defn input-component [*state]
  (r/create-class ;; TODO simplify
   {:component-did-mount #(let [el (key-handler/get-title-el)]
                            (editor/create el {:input-field-mode? true})
                            (.focus (key-handler/get-title-el)))
    :render (fn []
              [:input#search-input
               {:autoComplete :off
                :spellCheck   false
                :on-change    #(save-input-debounced! [*state %])
                :on-paste     #(save-input-debounced! [*state %])
                :on-cut       #(save-input-debounced! [*state %])
                :on-key-down  #(if (= "Backspace" (.-code %))
                                 (save-input-debounced! [*state (key-handler/get-title-el)])
                                 ((key-handler/handle-keys *state) %))}])}))

(defn component [*state]
  [:<>
   [:div.active-search-input-container [input-component *state]]
   (when (not (and (nil? (:selected-issue @*state))
                   (:selected-context @*state)
                   (= :issues (:active-search @*state))
                   (not (:search-globally? @*state))))
     [:div.mask.search-active
      {:on-click #(actions/quit-search! *state)}])])
