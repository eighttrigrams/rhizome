(ns ui.main.input
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.actions :as actions]
            [ui.main.input.key-handler :as key-handler]
            utils))

(defn save-input!
  [[*state evt]]
  (swap! *state assoc :q (.-value (or (.-target evt) evt)))
  (if (:vector-search-mode? @*state)
    (actions/vector-search! *state)
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
                      :class (when (:vector-search-mode? @*state) "vector-search-mode")
                      :spellCheck false
                      :on-change #(save-input-debounced! [*state %])
                      :on-paste #(save-input-debounced! [*state %])
                      :on-cut #(save-input-debounced! [*state %])
                      :on-blur #(when (:vector-search-mode? @*state)
                                  (swap! *state assoc :vector-search-mode? false)
                                  ;; Revert the vector-ranked list back to a normal
                                  ;; search when the user simply leaves the input.
                                  ;; Clicking a hit also blurs the input, but is
                                  ;; immediately followed by select-item! (which sets
                                  ;; :active-search nil and fetches the hit's context).
                                  ;; Running search! synchronously here would race with
                                  ;; and clobber that selection, so defer and only revert
                                  ;; when we're still in item-search once the click has
                                  ;; settled.
                                  (js/setTimeout
                                    (fn []
                                      (when (= :items (:active-search @*state))
                                        (actions/search! *state)))
                                    0))
                      :on-key-down #(if (= "Backspace" (.-code %))
                                      (save-input-debounced! [*state (key-handler/get-title-el)])
                                      ((key-handler/handle-keys *state) %))}])}))

(defn component
  [*state]
  [:<> [:div.active-search-input-container [input-component *state]]
   (when (not (and (:selected-item @*state) (= :items (:active-search @*state))))
     [:div.mask.search-active {:on-click #(actions/quit-search! *state)}])])
