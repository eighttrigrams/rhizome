(ns ui.main.rhs.context-badges)

(defn component [contexts]
  [:span.contexts
   (doall
    (map (fn [[idx title]]
           [:span.badge {:key idx}
            title]) contexts))])
