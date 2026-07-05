(ns ui.main.input
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.actions :as actions]
            [ui.main.input.key-handler :as key-handler]
            utils))

(defn save-input!
  [[*state evt]]
  (swap! *state assoc :q (.-value (or (.-target evt) evt)))
  (case (:vector-mode @*state)
    :green (actions/vector-search! *state)
    :blue  (actions/vector-threshold-search! *state)
    (actions/search! *state)))

(def save-input-debounced! (utils/debounce save-input! 180))

(defn input-component
  [*state]
  (r/create-class ;; TODO simplify
    {:component-did-mount #(let [el (key-handler/get-title-el)]
                             (editor/create el {:input-field-mode? true})
                             (.focus (key-handler/get-title-el)))
     :render (fn [] [:input#search-input
                     {:autoComplete :off
                      :class (case (:vector-mode @*state)
                               :green "vector-search-mode"
                               :blue "vector-search-mode blue"
                               nil)
                      :spellCheck false
                      :on-change #(save-input-debounced! [*state %])
                      :on-paste #(save-input-debounced! [*state %])
                      :on-cut #(save-input-debounced! [*state %])
                      ;; Deliberately no :on-blur handler. Clicking a vector-search hit
                      ;; blurs this input (on mousedown) *before* the item's click fires
                      ;; select-item!. Any fetch kicked off from on-blur (e.g. a
                      ;; "revert to normal search" search!) resets state from a stale
                      ;; snapshot and its async completion clobbers the selection, so the
                      ;; hit never becomes the selected item. Doing nothing on blur lets
                      ;; select-item! run uncontested — exactly like a normal item-search
                      ;; click. The :active-search->nil transition that selecting causes
                      ;; already clears :vector-mode via the add-watch in ui.cljs;
                      ;; a blur that doesn't select leaves the mode intact (vector list +
                      ;; vector label stay consistent).
                      :on-key-down #(if (= "Backspace" (.-code %))
                                      (save-input-debounced! [*state (key-handler/get-title-el)])
                                      ((key-handler/handle-keys *state) %))}])}))

(defn component
  [*state]
  [:<> [:div.active-search-input-container [input-component *state]]
   (when (not (and (:selected-item @*state) (= :items (:active-search @*state))))
     [:div.mask.search-active {:on-click #(actions/quit-search! *state)}])])
