(ns ui.main.context-badges)

(defn component [contexts]
  (prn ".." contexts)
  [:span.contexts
   (doall
    (map (fn [[idx title]]
           [:span.badge {:key idx}
            title]) contexts))])
