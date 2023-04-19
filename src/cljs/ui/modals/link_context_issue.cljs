(ns ui.modals.link-context-issue
  (:require [reagent.core :as r]
            api))

(defn- get-component-el []
  (.getElementById js/document "link-context-issue-component"))

(def *selectable-contexts (r/atom #{}))

(defn component [issue]
  (let [remove-context (fn [idx]
                         #(swap! *selectable-contexts
                                 (fn [vals]
                                   (let [new-vals (remove (fn [[k _v]] (= k idx)) vals)]
                                     (if (seq new-vals)
                                       (into {} new-vals)
                                       vals)))))] 
    
    (reset! *selectable-contexts (:contexts issue))
    
    (r/create-class
     {:component-did-mount #(.focus (get-component-el))
      :reagent-render      ;
      (fn [_selected-context _issue]
        [:<>
         [:h4 "Related contexts"]
         [:div#link-context-issue-component
          {:tabIndex 0}
          (map 
           (fn [[idx title]]
             [:div
              {:key idx
               :on-click      (remove-context idx)}
              title])
           @*selectable-contexts)]])})))

(defn get-values []
  (keys @*selectable-contexts))
