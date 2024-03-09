(ns ui.main.context-badges)

(defn component [contexts]
  [:span.contexts
   (doall
    (map (fn [[idx title]]
           (if (= :file idx)
             [:span.badge 
              {:key idx
               :on-click (fn [_]
                           (js/fetch (str "/open/" (js/encodeURI title))))}
              "🟢"]
             [:span.badge {:key idx}
              title])) 
         contexts))])
