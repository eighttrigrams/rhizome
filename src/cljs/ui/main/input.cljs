(ns ui.main.input
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.actions :as actions]))

(defn- get-title-el []
  (.getElementById js/document "search-input"))

(defn input-component [*state]
  (r/create-class
   {:component-did-mount #(let [el (get-title-el)]
                            (editor/create el {:input-field-mode? true})
                            (.focus (get-title-el)))
    :render (fn []
              [:input#search-input
               {:autoComplete :off
                :on-change    #(do (swap! *state assoc :q (.-value (.-target %)))
                                   (actions/search! *state))
                :on-key-down  #(let [code (.-code %)]
                                 (.stopPropagation %)
                                 (when (= code "Enter")
                                   (.preventDefault %)
                                   (cond
                                     (= :contexts (:active-search @*state))
                                     (do
                                       (actions/new-context! *state {:title (.-value (get-title-el))})
                                       (set! (.-value (get-title-el)) ""))
                                     ;; TODO extract this common pattern
                                     (and (not (:search-globally? @*state))
                                          (:selected-context @*state)
                                          (not (:selected-issue @*state)))
                                     (do (actions/new-issue! *state {:title (.-value (get-title-el))})
                                         (set! (.-value (get-title-el)) "")
                                         #_ (swap! *state dissoc nil))))
                                 (when (and (= code "KeyI")
                                            (.-altKey %))
                                   (.preventDefault %)
                                   (actions/start-global-search! *state))
                                 (when (and (= code "KeyC")
                                            (.-altKey %))
                                   (swap! *state dissoc :search-globally? :q :active-search)
                                   (actions/start-context-search *state))
                                 (when (and (= code "KeyD")
                                            (not (:selected-issue @*state))
                                            (:selected-context @*state)
                                            (.-altKey %))
                                   (actions/link-context-with-global-search! *state))
                                 (when (and (= code "KeyD")
                                            (.-altKey %)
                                            (:selected-issue @*state))
                                   (swap! *state assoc :active-search :contexts)
                                   (actions/start-linking-context *state))
                                 (when (= code "Enter")
                                   (when (= :contexts (:active-search @*state))
                                     (actions/select-first-context! *state))
                                   (when (= :issues (:active-search @*state))
                                     (actions/select-first-issue! *state)))
                                 (when (= code "Escape")
                                   (if (or (:search-globally? @*state)
                                           (:selected-issue @*state)
                                           ;; TODO (not (:selected-context @*state)) not necessary?
                                           ) 
                                     (actions/quit-search! *state)
                                     (if (seq (:selected-secondary-contexts-ids @*state))
                                       (actions/deselect-secondary-contexts! *state)
                                       (actions/quit-search! *state)))))}])}))

(defn component [*state]
  [:<>
   [:div.active-search-input-container [input-component *state]]
   (when (not (and (nil? (:selected-issue @*state))
                   (:selected-context @*state)
                   (= :issues (:active-search @*state))
                   (not (:search-globally? @*state))))
     [:div.mask.search-active
      {:on-click #(actions/quit-search! *state)}])])
