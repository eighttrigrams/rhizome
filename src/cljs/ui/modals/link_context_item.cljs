(ns ui.modals.link-context-item
  (:require [reagent.core :as r]
            api))

(defn- get-component-el [] (.getElementById js/document "link-context-item-component"))

(def *selectable-contexts (r/atom {}))

(defn component
  [item]
  (let [remove-context (fn [idx] #(swap! *selectable-contexts dissoc idx))]
    (reset! *selectable-contexts (:contexts (:data item)))
    (r/create-class
      {:component-did-mount #(.focus (get-component-el))
       :reagent-render ;
         (fn [_selected-item _item]
           #_(prn @*selectable-contexts)
           [:<> [:h4 "Related contexts"]
            [:div#link-context-item-component {:tabIndex 0}
             (map (fn [[idx {:keys [title annotation show-badge?]}]]
                    [:div {:key idx}
                     [:input
                      {:type :checkbox
                       :defaultChecked show-badge?
                       :value show-badge?
                       :on-click (fn [e]
                                   (swap! *selectable-contexts assoc-in
                                     [idx :show-badge?]
                                     (not= "true" (.-value (.-target e)))))}] " " title " "
                     [:span {:on-click (remove-context idx)} "[Remove]"]
                     [:input
                      {:value annotation
                       :on-change (fn [evt]
                                    (swap! *selectable-contexts assoc-in
                                      [idx :annotation]
                                      (.-value (.-target evt))))}]])
               @*selectable-contexts)]])})))

(defn get-values [] @*selectable-contexts)
