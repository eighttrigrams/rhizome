(ns ui.main.vector-threshold-slider
  (:require [ui.actions :as actions]))

;; Blue-mode overlay: a floating panel at the top-left of the LHS holding a
;; live "sim >= X" readout above a horizontal similarity-threshold slider.
;; Rendered only in :blue mode. Slider bounds come from the backend
;; (:vector-min/max-similarity); moving it calls set-vector-threshold!, which
;; updates the readout immediately and issues a debounced backend query — all
;; threshold filtering happens server-side.
(defn component
  [*state]
  (let [{:keys [vector-mode vector-threshold vector-max-similarity vector-min-similarity]}
        @*state]
    (when (= :blue vector-mode)
      [:div#vector-threshold-panel
       [:div.vector-threshold-readout
        "sim ≥ "
        [:span.vector-threshold-value
         (if (number? vector-threshold) (.toFixed vector-threshold 3) "—")]]
       [:input#vector-threshold-slider
        {:type "range"
         :min (or vector-min-similarity 0)
         :max (or vector-max-similarity 1)
         :step 0.001
         :value (if (number? vector-threshold)
                  vector-threshold
                  (or vector-max-similarity 0))
         :on-change #(actions/set-vector-threshold!
                       *state (js/parseFloat (.. % -target -value)))}]])))
