(ns ui.key-handler.common)

(defn handle-keys* [f]
  (fn [e]
    (let [code           (.-code e)
          ctrl-pressed?  (.-ctrlKey e)
          meta-pressed?  (.-metaKey e)
          alt-pressed?   (.-altKey e)
          shift-pressed? (.-shiftKey e)]
      (f code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? e))))
