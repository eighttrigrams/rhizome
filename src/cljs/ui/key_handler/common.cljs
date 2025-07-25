(ns ui.key-handler.common)

(defn something-to-deselect? [*state]
  (or (seq (:selected-secondary-contexts
            (:current
             (:views
              (:data
               (:selected-item @*state))))))
      (:secondary-contexts-unassigned-selected
       (:current
        (:views
         (:data
          (:selected-item @*state)))))
      (:secondary-contexts-inverted
       (:current
        (:views
         (:data
          (:selected-item @*state)))))
      (:search-mode
       (:current
        (:views
         (:data
          (:selected-item @*state)))))
      (:search-view
       (:current
        (:views
         (:data
          (:selected-item @*state)))))))

(defn handle-keys* [f]
  (fn [e]
    (let [code           (.-code e)
          ctrl-pressed?  (.-ctrlKey e)
          meta-pressed?  (.-metaKey e)
          alt-pressed?   (.-altKey e)
          shift-pressed? (.-shiftKey e)]
      (prn "code:" code "meta-pressed:" meta-pressed? "alt-pressed?" alt-pressed?)
      (f code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? e))))
