(ns ui.main.context-badges 
  (:require
    [ui.actions :as actions]))

(defn component [*state contexts]
  [:span.contexts
   (doall
    (map (fn [[idx {:keys [title date file number context show-badge?]}]]
           (case idx
             :file
             [:span.badge.light 
              {:key      idx
               :on-click (fn [e]
                           (.stopPropagation e)
                           (js/fetch (str "/open/" (js/encodeURI file))))}
              "🟢"]
             0
             [:span.badge.light
              {:key      idx
               :on-click (fn [e] 
                           (.stopPropagation e)
                           (let [callback-fn context]
                             (callback-fn)))}
              "⭕"]
             :date
             [:span.badge.light
              {:key :date} date]
             :number
             [:span.badge.light
              {:key :number} number] 
             (when show-badge?
               [:span.badge {:key idx
                             :on-click (fn [e]
                                         (.stopPropagation e)
                                         (when-not (or (:link-context @*state)
                                                       (:link-issue @*state))
                                           (actions/select-context! *state {:id idx})))} title]))) 
         contexts))])
