(ns ui.main.diff
  (:require ["@codemirror/merge" :refer [MergeView unifiedMergeView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/lang-markdown" :refer [markdown]]))

(defn- close! [*state] (swap! *state #(dissoc % :diff-view? :diff-unified?)))

(def ^:private editor-theme
  (.theme EditorView
          #js {"&" #js {:backgroundColor "wheat" :color "#333"}
               ".cm-content" #js {:caretColor "#333"}
               ".cm-gutters" #js {:backgroundColor "wheat"
                                  :color "#8a8574"
                                  :border "none"}}))

(defn- base-extensions
  []
  [(markdown)
   (.-lineWrapping EditorView)
   editor-theme
   (.of (.-editable EditorView) false)
   (.of (.-readOnly EditorState) true)])

(defn- mount-diff!
  [el older newer unified?]
  (if unified?
    (EditorView. #js {:doc newer
                      :extensions (into-array (conj (base-extensions)
                                                    (unifiedMergeView #js {:original older
                                                                           :mergeControls false})))
                      :parent el})
    (MergeView. #js {:a #js {:doc older :extensions (into-array (base-extensions))}
                     :b #js {:doc newer :extensions (into-array (base-extensions))}
                     :parent el})))

(defn- diff-editor
  [_older _newer _unified?]
  (let [*view (atom nil)]
    (fn [older newer unified?]
      [:div.diff-editor
       {:ref (fn [el]
               (if el
                 (reset! *view (mount-diff! el (or older "") (or newer "") unified?))
                 (when-let [view @*view] (.destroy view) (reset! *view nil))))}])))

(defn component
  [*state]
  (let [{:keys [selected-item item-descriptions description-version-idx diff-unified?]} @*state
        total (count item-descriptions)
        max-idx (- total 2)
        version-idx (max 0 (min (or description-version-idx 0) max-idx))
        newer (nth item-descriptions version-idx nil)
        older (nth item-descriptions (inc version-idx) nil)]
    [:div#diff-page
     [:div.config-header
      [:button.config-close {:on-click #(close! *state) :title "Close"} "✕"]
      [:h2 "Diff"]
      [:button
       {:on-click #(swap! *state assoc :description-version-idx (inc version-idx))
        :disabled (>= version-idx max-idx)} "←"]
      [:button
       {:on-click #(swap! *state assoc :description-version-idx (dec version-idx))
        :disabled (<= version-idx 0)} "→"]
      [:span.diff-version-label
       (when (and older newer)
         (let [version-label (fn [{:keys [version source]}]
                               (str "Version " version (when source (str " · " source))))]
           (str (version-label older)
                " → " (version-label newer)
                (when (zero? version-idx) " (current)"))))]
      [:button.diff-mode-toggle
       {:on-click #(swap! *state update :diff-unified? not)}
       (if diff-unified? "Split" "Unified")]]
     (if older
       ^{:key (str (:id selected-item) "-" version-idx "-" (boolean diff-unified?))}
       [diff-editor (:text older) (:text newer) diff-unified?]
       [:p "No previous version to compare against."])]))
