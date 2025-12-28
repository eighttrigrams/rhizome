(ns ui.key-handler.common)

(defn something-to-deselect?
  [*state]
  (let [current (-> @*state
                    :selected-item
                    :data
                    :views
                    :current)]
    (or (seq (:selected-secondary-contexts current))
        (:secondary-contexts-unassigned-selected current)
        (:secondary-contexts-inverted current)
        (:search-mode current)
        (:search-view current)
        (some? (:description-filter current)))))

(defn handle-keys*
  [f]
  (fn [e]
    (let [code (.-code e)
          ctrl-pressed? (.-ctrlKey e)
          meta-pressed? (.-metaKey e)
          alt-pressed? (.-altKey e)
          shift-pressed? (.-shiftKey e)]
      (prn "code:" code "meta-pressed:" meta-pressed? "alt-pressed?" alt-pressed?)
      (f code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? e))))
