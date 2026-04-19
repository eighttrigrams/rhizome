(ns ui.recording-mode)

(defn- json-handler
  [*state]
  (fn [^js resp]
    (-> resp
        (.json)
        (.then (fn [^js data]
                 (swap! *state assoc :recording-mode? (boolean (.-recording data))))))))

(defn fetch-state!
  [*state]
  (-> (js/fetch "/rest/recording-mode")
      (.then (json-handler *state))))

(defn toggle!
  [*state]
  (-> (js/fetch "/rest/recording-mode/toggle" #js {:method "POST"})
      (.then (json-handler *state))))

(defn indicator
  [*state]
  (when (:recording-mode? @*state)
    [:div#recording-indicator
     {:title "Recording mode — REST writes are enabled"
      :style {:position "fixed"
              :top "6px"
              :left "6px"
              :z-index 10000
              :background "#c0392b"
              :color "white"
              :padding "4px 8px"
              :border-radius "4px"
              :font-size "12px"
              :font-weight "bold"
              :box-shadow "0 1px 3px rgba(0,0,0,0.3)"
              :pointer-events "none"
              :user-select "none"}}
     "⚠ REC"]))
