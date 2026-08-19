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
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/commands" :as commands]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

(defn create-editor
  "Create CodeMirror 6 editor with cljs-text-editor-style keyboard handling"
  [element config]
  (let [doc (or (:doc config) "")
        ;; The editor's outer box is the caller's to size, because the two places
        ;; it is used are not the same kind of thing. The description modal is a
        ;; page and takes the viewport; the relation modal's text is one field
        ;; among several and takes a slice of it, growing with the text up to a
        ;; cap. Either way .cm-scroller below does the scrolling inside the box,
        ;; so the page never grows a second scrollbar.
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
        ;; No red squiggles under the prose. The editing surface is a
        ;; contenteditable, which the browser spellchecks by default, and nothing
        ;; in this app underlines words.
        no-spellcheck (.of (.-contentAttributes EditorView) #js {:spellcheck "false"})
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
