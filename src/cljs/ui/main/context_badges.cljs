(ns ui.main.context-badges)

(defn component [contexts]
  [:span.contexts
   (doall
    (map (fn [[idx title]]
           (case idx 
             :file
             [:span.badge.light 
              {:key      idx
               :on-click (fn [e]
                           (.stopPropagation e)
                           (js/fetch (str "/open/" (js/encodeURI title))))}
              "🟢"]
             0
             [:span.badge.light
              {:key      idx
               :on-click (fn [e] 
                           (.stopPropagation e)
                           (let [callback-fn title]
                             (callback-fn)))}
              "⭕"]
             :date
             [:span.badge.light
              {:key :date} title]
             :number
             [:span.badge.light
              {:key :number} title]
             [:span.badge {:key idx} title])) 
         contexts))])
