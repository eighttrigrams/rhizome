(ns ui.recording-mode)

(defn- json-handler
  [*state]
  (fn [^js resp]
    (-> resp
        (.json)
        (.then (fn [^js data]
                 (let [{:keys [recording read-only-replica error]}
                         (js->clj data :keywordize-keys true)]
                   ;; Toggling the gate is itself a write, so a read-only replica
                   ;; refuses it -- report that instead of leaving the keypress
                   ;; looking like a no-op.
                   (if read-only-replica
                     (js/window.alert error)
                     (swap! *state assoc :recording-mode? (boolean recording)))))))))

(defn toggle!
  [*state]
  (-> (js/fetch "/api/recording-mode/toggle"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify
                             #js {:reason "in-app keyboard shortcut Option+Shift+W"})})
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
