(ns ui.codemirror
  "The editor behind the description modal.

  The keyboard scheme is not here any more. It used to be: a 47-chord table and
  eleven hand-written commands, of which tracker and treina each held a
  near-identical copy. All of that is now `@eighttrigrams/kw-codemirror`, the
  library in the keyboard-wizardry repo that also holds Daniel's VSCode and
  Obsidian keymaps of the same scheme — one implementation rather than one per
  app. The 47 chords are the same 47.

  Two of them behave differently on purpose. ctrl+j and ctrl+l were line start
  and end here; in the library they are the markdown \"sentence\" motions — the
  block, or just the line when the one above it ended in a hard break. That is
  blog's behaviour and the only one the scheme's README defines, so the apps were
  unified onto it, and ctrl+shift+j / ctrl+shift+l select as far as those now
  move. Separately, the four option motions gained a second meaning inside a
  fenced ```clojure block, where they move by form instead of by word and line.

  What stays here is what is rhizome's: the wheat theme, basicSetup and the
  markdown extension, and the __codemirror handle on the element that
  ui.modals and the e2e steps read the editor back out of."
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView placeholder]]
            ["@codemirror/commands" :as commands]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

;; No red squiggles under the prose. The editing surface is a contenteditable,
;; which the browser spellchecks by default, and nothing in this app underlines
;; words. index.html says the same thing once, for the whole document, on <body>;
;; this is here as well because it is the one place a *derived* element gets its
;; attributes, and an inherited default is a weaker promise than an explicit no.
(def ^:private no-spellcheck (.of (.-contentAttributes EditorView) #js {:spellcheck "false"}))

(defn create-editor
  "Create CodeMirror 6 editor with cljs-text-editor-style keyboard handling"
  [element config]
  (let [doc (or (:doc config) "")
        ;; The editor's outer box is the caller's to size, because the two places
        ;; it is used are not the same kind of thing: the description modal is a
        ;; page and takes the viewport, the relation modal's text is one field
        ;; among several and takes a slice of it. Both give it a definite height
        ;; and let .cm-scroller below do the scrolling inside that -- CodeMirror
        ;; stretches its content and its gutter to a height it has been given and
        ;; not to one it has merely been allowed, so a min/max pair leaves the
        ;; editing surface one line tall inside a taller box.
        box (merge {:backgroundColor "wheat" :border "1px solid #ddd"}
                   (or (:box config) {:height "90vh" :minHeight "500px"}))
        ;; Create custom theme with wheat-friendly colors throughout
        custom-theme
          (.theme EditorView
                  #js {"&" (clj->js box)
                       ".cm-scroller" #js {:overflow "auto"}
                       ".cm-content" #js {:padding "10px"}
                       ".cm-focused" #js {:outline "none"}
                       ;; Selection colors - creamy yellow
                       ".cm-selectionBackground" #js {:backgroundColor "rgba(255, 218, 121, 0.6)"}
                       "&.cm-focused .cm-selectionBackground" #js {:backgroundColor
                                                                     "rgba(255, 218, 121, 0.8)"}
                       ;; Active line - whitish cream
                       ".cm-activeLine" #js {:backgroundColor "rgba(255, 250, 240, 0.5)"}
                       ".cm-activeLineGutter" #js {:backgroundColor "rgba(255, 250, 240, 0.3)"}
                       ;; Search match colors - almost invisible, like line highlight
                       ".cm-searchMatch" #js {:backgroundColor "rgba(255, 250, 240, 0.4)"
                                              :border "1px solid rgba(240, 235, 225, 0.2)"}
                       ".cm-searchMatch.cm-searchMatch-selected" #js {:backgroundColor
                                                                        "rgba(255, 218, 121, 0.8)"}
                       ;; Selection match highlighting - this is the key one!
                       ".cm-selectionMatch"
                         #js {:backgroundColor "rgba(245, 255, 235, 0.4) !important" :border "none"}
                       ;; Additional match highlighting classes
                       ".cm-matchingBracket" #js {:backgroundColor "rgba(255, 250, 240, 0.4)"}
                       ".cm-nonmatchingBracket" #js {:backgroundColor "rgba(255, 250, 240, 0.4)"}
                       ".cm-highlightSelectionMatches" #js {:backgroundColor
                                                              "rgba(255, 250, 240, 0.4)"}})
        ;; Build extensions array with line wrapping
        line-wrapping (.-lineWrapping EditorView)
        extensions (if (:markdown? config)
                     #js [basicSetup (markdown) custom-theme line-wrapping no-spellcheck]
                     #js [basicSetup custom-theme line-wrapping no-spellcheck])
        ;; Create editor state
        state (.create EditorState #js {:doc doc :extensions extensions})
        ;; Create editor view
        view (new EditorView #js {:state state :parent element})]
    ;; Store reference for later access
    (aset element "__codemirror" view)
    ;; The scheme. install puts a capture-phase keydown listener on the view's
    ;; own element, so these chords win before CodeMirror's keymaps — which is
    ;; what the listener this replaces did, one level further out on `element`.
    (ijkl/install view commands)
    ;; Focus if requested
    (when (:focus? config) (.focus view))
    view))

(defn get-editor-value
  "Get current content from CodeMirror editor"
  [view]
  (when view (.. view -state -doc toString)))

(defn set-editor-value
  "Set content in CodeMirror editor"
  [view value]
  (when view
    (let [transaction
            (.update (.-state view)
                     #js {:changes #js {:from 0 :to (.. view -state -doc -length) :insert value}})]
      (.dispatch view transaction))))

;; ---------------------------------------------------------------------------
;; One-line editors, for input fields
;;
;; The same scheme on an <input>, which is a different shape of problem from the
;; description box above and not a smaller one. It replaces the hand-written
;; editor that used to do this job -- net.eighttrigrams.cljs-text-editor, vendored
;; into src/cljs and wired onto the search box and three of the edit modal's
;; fields. That one hung native keydown listeners on the <input> and edited
;; .value and .selectionStart by hand; this one is the library's `input` mode --
;; the same layout the description editor above wears, less the chords a document
;; has and a field has nowhere to put. One implementation of the scheme rather
;; than two, and the same one tracker now runs.
;;
;; Three things had to be true at once, and each of them rules out the obvious
;; approach of replacing the <input> with a contenteditable:
;;
;;   it has to look right        rhizome styles these fields through *element*
;;                               selectors -- `#modal-component input.line`,
;;                               `#search-input` -- so a div wearing the input's
;;                               classes matches none of it and there is nothing
;;                               to copy the look from by class. What there is, is
;;                               the input's *computed* style: the resolved
;;                               answer, however the cascade arrived at it.
;;
;;   it has to stay readable     the edit modal reads its fields back out of the
;;                               DOM at save time -- getElementById + .value, in
;;                               item-edit/get-values -- and so does the search
;;                               box's key handler, on every Enter. The e2e suite
;;                               fills #item-title and #search-input and reads
;;                               .value back off them. A contenteditable div has
;;                               none of that.
;;
;;   it has to stay in the flow  #search-input's 720px is what gives
;;                               .active-search-input-container (position:
;;                               absolute, no width of its own) its width.
;;
;; All three are answered by not removing the input. It stays exactly where it
;; was, keeps its id, and is made transparent rather than hidden -- so the CSS
;; still resolves against it, getElementById still finds it, Playwright still
;; fills it, and the editor is themed from what it computes to. The same trick the
;; library's own fromTextarea uses, and for the same reasons.

(defn- input-theme
  "The editor, dressed as the <input> it stands in front of.

   Read off the live element rather than written out here, because there is no one
   answer to write: `#modal-component input.line` and `#search-input` describe
   two quite different boxes. Whatever the cascade concluded, this copies.

   The height is the exception, and it is taken from offsetHeight rather than from
   the computed `height`. `input.line` sets `height: 20px`, which with the default
   box-sizing is a *content* height -- 20px plus 10px of padding on each side, so
   the box is 40px and the computed property says 20. Hand CodeMirror the 20 and
   the field's own padding pushes its single line out of a box a third too short,
   with .cm-scroller's overflow-y hidden clipping the result. offsetHeight is the
   box, which is the thing being reproduced."
  [input]
  (let [css (js/window.getComputedStyle input)
        g   #(.getPropertyValue css %)]
    (.theme EditorView
            #js {"&" #js {:height (str (.-offsetHeight input) "px")
                          :boxSizing "border-box"
                          :fontFamily (g "font-family")
                          :fontSize (g "font-size")
                          :fontWeight (g "font-weight")
                          :letterSpacing (g "letter-spacing")
                          :color (g "color")
                          :backgroundColor (g "background-color")
                          :border (g "border")
                          :borderRadius (g "border-radius")}
                 ;; `#modal-component input:focus { outline: none }`, said about the
                 ;; element that now draws the box.
                 "&.cm-focused" #js {:outline "none"}
                 ".cm-scroller" #js {:fontFamily (g "font-family")
                                     :lineHeight (g "line-height")
                                     ;; sideways, never down: one line that pans,
                                     ;; which is what an input does with a value
                                     ;; too long for its box.
                                     :overflowX "auto"
                                     :overflowY "hidden"}
                 ".cm-content" #js {:padding (g "padding")
                                    :fontFamily (g "font-family")
                                    :caretColor (g "color")}
                 ".cm-line" #js {:padding "0"}
                 ".cm-gutters" #js {:display "none"}
                 ".cm-activeLine" #js {:backgroundColor "transparent"}
                 ".cm-cursor" #js {:borderLeftColor (g "color")}})))

;; The transparent-and-in-place treatment. Not display:none, and not for a
;; cosmetic reason: a field with no bounding box is one Playwright will not
;; consider fillable, and the suite fills these.
(def ^:private hidden-input-style
  #js {:position "absolute"
       :inset "0"
       ;; border-box, or the field's own border and padding are *added* to the
       ;; 100% and the mirror sits a few pixels wider and taller than the box it
       ;; is meant to cover.
       :boxSizing "border-box"
       :width "100%"
       :height "100%"
       :margin "0"
       :opacity "0"
       :pointerEvents "none"})

(def ^:private *echo?
  "Whether a document change is reported onwards to the field's :on-change.

   False for the duration of a set-field-value! -- a write the app made itself,
   which it does not need telling about. It matters for the search box: the key
   handler empties the field after creating a context or an item, and it did that
   with `set! .-value`, which fires no event and starts no search. Echoing it
   would put a search for the empty string on the end of every creation, arriving
   180ms late, on top of whatever the creation itself left on screen.

   An atom rather than a parameter because the write is dispatched synchronously,
   so the listener runs inside the binding and JavaScript has no other thread to
   see it."
  (atom true))

(defn create-input-editor
  "A one-line editor in `host`, themed from and mirrored onto `input`.

   The document starts as whatever the element already holds -- these fields are
   uncontrolled, rendered with :defaultValue, so the DOM *is* where the initial
   text lives -- and every change to it is written back into .value, so anything
   reading the field the old way reads the truth.

   Options:
     :on-change   called with the new text after the document changed, except for
                  writes the app made itself. See *echo?.
     :app-chords  chords the app owns on this field, taken out of the layout. See
                  below.

   Returns the view, which is also left on the element as .__codemirror -- the
   handle this app already uses for the description editor, in the modal and in
   the e2e steps."
  [host input {:keys [on-change app-chords]}]
  (let [doc (ijkl/oneLine (or (.-value input) ""))
        cm  #js {:EditorState EditorState :EditorView EditorView}
        mirror (.of (.-updateListener EditorView)
                    (fn [^js update]
                      (when (.-docChanged update)
                        (let [text (.. update -state -doc toString)]
                          (set! (.-value input) text)
                          (when (and on-change @*echo?) (on-change text))))))
        extensions (.concat #js [(input-theme input) no-spellcheck]
                            (.concat (ijkl/singleLine cm)
                                     #js [(placeholder (or (.-placeholder input) ""))
                                          mirror]))
        state (.create EditorState #js {:doc doc :extensions extensions})
        view  (new EditorView #js {:state state :parent host})
        ;; The scheme, in its one-line layout: no line motions, no block motions,
        ;; no fenced-Clojure structural editing, and ctrl+j / ctrl+l are line
        ;; start and end. Enter, Escape, Tab and the arrows are in neither layout,
        ;; so they are neither preventDefaulted nor stopped and still reach
        ;; whatever the app has on the field -- which here is "create this item",
        ;; "quit the search", and the modal's own save-and-close.
        ;;
        ;; :app-chords is for the ones that *are* in the layout and are also the
        ;; app's. install() stops what it finds in its table, so a chord left in
        ;; it never reaches the field's own handler -- and the search box has two
        ;; that must: option+a links the found item to the selected one, option+c
        ;; switches to context search. Both are working keys today, because the
        ;; editor this replaces bound neither and stopped nothing. Deleting them
        ;; from the table is the library's own answer to this (install takes a
        ;; caller's table for exactly this reason), and it costs the scheme's
        ;; select-all and copy in that one box.
        table (ijkl/bindings commands #js {:mode (.-INPUT ijkl)})]
    (doseq [chord app-chords] (js-delete table chord))
    (set! (.-value input) doc)
    (.assign js/Object (.-style input) hidden-input-style)
    ;; Out of the tab order, but still focusable on request -- which is what
    ;; .focus() and Playwright both do. Left in it, Tab would walk title, mirror,
    ;; editor, mirror, and half those stops are a box you cannot see your caret
    ;; in. The old editor dropped its two Tab chords for the same reason: in a
    ;; field, Tab belongs to the form.
    (set! (.-tabIndex input) -1)
    (aset input "__codemirror" view)
    (ijkl/install view commands #js {:table table})
    ;; No focus forwarding from the mirror, and that is a correction rather than
    ;; an omission. Handing focus to the editor the moment the <input> received it
    ;; is what the library's fromTextarea does, and it breaks filling the field
    ;; programmatically: Playwright's fill() focuses the element and then inserts
    ;; text into whatever is focused *now*, so with the focus already passed on
    ;; the insert lands in the editor at caret 0 and leaves the old value sitting
    ;; behind it. The app's own focusing goes through focus-field! below instead,
    ;; which asks for the editor by name.
    ;;
    ;; fill() sets .value and fires `input`; without this listener the editor
    ;; would never hear about it and the document and the field would disagree.
    (.addEventListener input "input"
                       (fn [_]
                         (let [v (ijkl/oneLine (or (.-value input) ""))]
                           (when-not (= v (.. view -state -doc toString))
                             (set-editor-value view v)
                             (when on-change (on-change v))))))
    view))

(defn field-view
  "The one-line editor standing in front of field `el`, or nil."
  [^js el]
  (when el (.-__codemirror el)))

(defn focus-field!
  "Focus field `el` -- the editor in front of it, when there is one.

   The two callers are the app putting the caret where the user expects it: the
   search box on mount and after every click in the context detail, and the edit
   modal on its title. Both used to call .focus() on the element, which now would
   focus the transparent mirror and leave the visible box dark.

   The scroll is the part that is easy to leave out and expensive to leave out.
   CodeMirror's own focus() is deliberately focusPreventScroll -- it does not want
   an editor yanking the page about -- and a browser focusing an <input> does the
   opposite: it scrolls the thing into view. That difference is visible here
   because #sides-container is 1440px wide and a laptop window is not, so the
   right-hand column's search box hangs over the edge until something scrolls to
   it. It always had. Losing that meant typing into a box with its last 165px off
   screen -- and, less obviously, it moved every card 160px sideways, which is how
   the e2e suite noticed first: a pointer left resting where a strip used to be
   came back after a reload over a different card entirely.

   `nearest` in both axes, so a box already on screen is not moved."
  [el]
  (when el
    (if-let [^js view (field-view el)]
      (do (.focus view) (.scrollIntoView (.-dom view) #js {:block "nearest" :inline "nearest"}))
      (.focus el))))

(defn set-field-value!
  "Write `value` into field `el`, editor and .value both, without echoing it back
   to the field's :on-change. See *echo?."
  [el value]
  (when el
    (if-let [view (field-view el)]
      (do (reset! *echo? false)
          (try (set-editor-value view value) (finally (reset! *echo? true))))
      (set! (.-value el) value))))
