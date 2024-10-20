(ns ui.modals.link-context-issue
  (:require [reagent.core :as r]
            api))

(defn- get-component-el []
  (.getElementById js/document "link-context-issue-component"))

(def *selectable-contexts (r/atom {}))

(defn component [issue]
  (let [remove-context (fn [idx] #(swap! *selectable-contexts dissoc idx))] 
    
    (reset! *selectable-contexts (:contexts (:data issue)))
    
    (r/create-class
     {:component-did-mount #(.focus (get-component-el))
      :reagent-render      ;
      (fn [_selected-context _issue]
        (prn @*selectable-contexts)
        [:<>
         [:h4 "Related contexts"]
         [:div#link-context-issue-component
          {:tabIndex 0}
          (map 
           (fn [[idx {:keys [title show-badge?]}]]
             (prn "idx" title show-badge?)
             [:div
              {:key idx}
              [:input {:type :checkbox
                       :defaultChecked show-badge?
                       :value show-badge?
                       :on-click (fn [e]
                                   (swap! *selectable-contexts
                                          assoc-in
                                          [idx :show-badge?] 
                                          (not= "true" (.-value (.-target e)))))}]
              " "
              title
              " "
              [:span {:on-click (remove-context idx)}
               "[Remove]"]])
           @*selectable-contexts)]])})))

(defn get-values []
  @*selectable-contexts)
