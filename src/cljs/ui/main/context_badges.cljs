(ns ui.main.context-badges
  (:require [ui.actions :as actions]))

(defn component [contexts]
  [:span.contexts
   (doall
    (map (fn [[idx title]]
           (case idx 
             :file
                 [:span.badge 
                  {:key idx
                   :on-click (fn [_]
                               (js/fetch (str "/open/" (js/encodeURI title))))}
                  "🟢"]
                 0
                 [:span.badge 
                  {:key idx
                   :on-click (fn [_] (title))}
                  "⭕"]
                 [:span.badge {:key idx} title])) 
         contexts))])
