(ns ui.main.input
  (:require [reagent.core :as r]
            [ui.codemirror :as codemirror]
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

(defn- vector-mode-class
  [*state]
  (case (:vector-mode @*state) :green "vector-search-mode" :blue "vector-search-mode blue" nil))

(defn input-component
  [*state]
  (let [*editor (atom nil)]
    (r/create-class ;; TODO simplify
      {:component-did-mount
         #(let [el (key-handler/get-title-el)]
            (reset! *editor
              (codemirror/create-input-editor
                (.-parentElement el) el
                {;; The text is handed back the way save-input! already reads it:
                 ;; off the element, whose .value the editor has just written. It
                 ;; is the same call the Backspace branch of :on-key-down used to
                 ;; make -- see below.
                 :on-change (fn [_text] (save-input-debounced! [*state el]))
                 ;; The two chords this box owns; see create-input-editor.
                 :app-chords ["KeyA alt" "KeyC alt"]}))
            (codemirror/focus-field! el))
       :component-will-unmount #(when-let [view @*editor] (.destroy view) (reset! *editor nil))
       ;; The handlers are on the wrapper rather than on the <input>, because the
       ;; keystrokes now arrive at the editor's contenteditable, which is a plain
       ;; DOM child of it and no React element of ours. React dispatches an event
       ;; from an unmanaged descendant on the nearest managed ancestor, so this
       ;; catches both -- what is typed into the editor, and what Playwright
       ;; presses on the <input> itself.
       ;;
       ;; The class is on both. The wrapper is what CSS can reach the visible box
       ;; through (layout.css); the <input> keeps it because that is where the e2e
       ;; suite asserts vector mode.
       :render
         (fn []
           [:div#search-input-host
            {:class (vector-mode-class *state)
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
             ;;
             ;; Backspace used to be special-cased here, to re-read the element
             ;; because the old editor deleted the character by writing .value
             ;; and fired no event anybody could hear. In the editor it is an
             ;; ordinary edit, and an ordinary edit reaches :on-change.
             :on-key-down (key-handler/handle-keys *state)}
            [:input#search-input
             {:autoComplete :off :class (vector-mode-class *state) :spellCheck false}]])})))

(defn component
  [*state]
  [:<> [:div.active-search-input-container [input-component *state]]
   (when (not (and (:selected-item @*state) (= :items (:active-search @*state))))
     [:div.mask.search-active {:on-click #(actions/quit-search! *state)}])])
