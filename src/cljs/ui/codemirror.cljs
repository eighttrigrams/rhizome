(ns ui.codemirror
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/commands" :as commands]
            ["@codemirror/lang-markdown" :refer [markdown]]))

;; Direct key to command mapping
(def key-commands
  {;; Basic movement
   #{"KeyJ" #{:meta}} commands/cursorCharLeft
   #{"KeyL" #{:meta}} commands/cursorCharRight
   #{"KeyI" #{:meta}} commands/cursorLineUp
   #{"KeyK" #{:meta}} commands/cursorLineDown
   #{"KeyJ" #{:alt}} commands/cursorGroupLeft
   #{"KeyL" #{:alt}} commands/cursorGroupRight
   #{"KeyI" #{:alt}} commands/cursorLineUp
   #{"KeyK" #{:alt}} commands/cursorLineDown
   #{"KeyJ" #{:ctrl}} commands/cursorLineStart
   #{"KeyL" #{:ctrl}} commands/cursorLineEnd
   ;; Selection variants
   #{"KeyJ" #{:meta :shift}} commands/selectCharLeft
   #{"KeyL" #{:meta :shift}} commands/selectCharRight
   #{"KeyI" #{:meta :shift}} commands/selectLineUp
   #{"KeyK" #{:meta :shift}} commands/selectLineDown
   #{"KeyJ" #{:alt :shift}} commands/selectGroupLeft
   #{"KeyL" #{:alt :shift}} commands/selectGroupRight
   #{"KeyI" #{:alt :shift}} commands/selectLineUp
   #{"KeyK" #{:alt :shift}} commands/selectLineDown
   #{"KeyJ" #{:ctrl :shift}} commands/selectLineStart
   #{"KeyL" #{:ctrl :shift}} commands/selectLineEnd
   ;; Delete operations
   #{"Equal" #{:alt}} commands/deleteGroupForward
   #{"Equal" #{:meta}} commands/deleteCharForward
   #{"Backspace" #{:ctrl}} commands/deleteToLineStart
   #{"Equal" #{:ctrl}} commands/deleteToLineEnd
   #{"Equal" #{:ctrl :meta}} commands/deleteLine
   ;; Line operations
   #{"Enter" #{:shift}} :custom-new-line-below
   #{"Enter" #{:meta}} :custom-new-line-above
   #{"KeyI" #{:ctrl :meta}} commands/moveLineUp
   #{"KeyK" #{:ctrl :meta}} commands/moveLineDown
   ;; Indentation
   #{"KeyL" #{:ctrl :meta}} commands/indentMore
   #{"KeyJ" #{:ctrl :meta}} commands/indentLess
   ;; Page navigation
   #{"KeyP" #{:alt :meta}} commands/cursorPageUp
   #{"Semicolon" #{:alt :meta}} commands/cursorPageDown
   ;; Viewport scrolling (without moving cursor)
   #{"KeyI" #{:alt :meta :shift}} :custom-scroll-down
   #{"KeyK" #{:alt :meta :shift}} :custom-scroll-up
   ;; Viewport + cursor movement
   #{"KeyI" #{:alt :meta}} :custom-cursor-viewport-up
   #{"KeyK" #{:alt :meta}} :custom-cursor-viewport-down
   ;; Document navigation
   #{"KeyP" #{:ctrl :alt :meta}} commands/cursorDocStart
   #{"Semicolon" #{:ctrl :alt :meta}} commands/cursorDocEnd
   ;; Center caret/line in viewport
   #{"Semicolon" #{:meta}} :custom-center-caret
   #{"Semicolon" #{:ctrl :meta}} :custom-center-line
   ;; Select all
   #{"KeyA" #{:alt}} commands/selectAll
   ;; Undo/Redo
   #{"Backquote" #{:alt}} commands/undo
   #{"Backquote" #{:shift}} commands/redo
   ;; Clipboard operations (custom implementations)
   #{"KeyC" #{:alt}} :custom-copy
   #{"KeyV" #{:alt}} :custom-paste
   #{"KeyX" #{:alt}} :custom-cut})

;; Custom clipboard operations
(defn custom-copy
  [view]
  (let [selection (.. view -state -selection -main)]
    (when-not (= (.-from selection) (.-to selection))
      (let [text (.. view -state -doc (slice (.-from selection) (.-to selection)))]
        (.writeText js/navigator.clipboard text)))))

(defn custom-paste
  [view]
  (.then (.readText js/navigator.clipboard)
         (fn [text]
           (let [selection (.. view -state -selection -main)
                 transaction (.update (.-state view)
                                      #js {:changes #js {:from (.-from selection)
                                                         :to (.-to selection)
                                                         :insert text}})]
             (.dispatch view transaction)))))

(defn custom-cut
  [view]
  (let [selection (.. view -state -selection -main)]
    (when-not (= (.-from selection) (.-to selection))
      (let [text (.. view -state -doc (slice (.-from selection) (.-to selection)))]
        (.writeText js/navigator.clipboard text)
        (let [transaction (.update (.-state view)
                                   #js {:changes #js {:from (.-from selection)
                                                      :to (.-to selection)
                                                      :insert ""}})]
          (.dispatch view transaction))))))

(defn custom-new-line-below
  "Insert a new line below current line and move cursor to it"
  [view]
  (let [state (.-state view)
        cursor (.. state -selection -main -head)
        doc (.-doc state)
        line-info (.lineAt ^js doc cursor)
        line-end (.-to line-info)
        transaction (.update state
                             #js {:changes #js {:from line-end :to line-end :insert "\n"}
                                  :selection #js {:anchor (inc line-end) :head (inc line-end)}})]
    (.dispatch view transaction)))

(defn custom-new-line-above
  "Insert a new line above current line and move cursor to it"
  [view]
  (let [state (.-state view)
        cursor (.. state -selection -main -head)
        doc (.-doc state)
        line-info (.lineAt ^js doc cursor)
        line-start (.-from line-info)
        transaction (.update state
                             #js {:changes #js {:from line-start :to line-start :insert "\n"}
                                  :selection #js {:anchor line-start :head line-start}})]
    (.dispatch view transaction)))

(defn custom-scroll-up
  "Scroll viewport up by one line without moving cursor"
  [view]
  (let [line-height 20 ; Fixed line height approximation
        scroll-dom ^js (.-scrollDOM view)]
    (set! (.-scrollTop scroll-dom) (- (.-scrollTop scroll-dom) line-height))))

(defn custom-scroll-down
  "Scroll viewport down by one line without moving cursor"
  [view]
  (let [line-height 20 ; Fixed line height approximation
        scroll-dom ^js (.-scrollDOM view)]
    (set! (.-scrollTop scroll-dom) (+ (.-scrollTop scroll-dom) line-height))))

(defn custom-cursor-viewport-up
  "Move cursor up one line and scroll viewport up"
  [view]
  (let [line-height 20 ; Fixed line height approximation
        scroll-dom ^js (.-scrollDOM view)]
    ;; Move cursor up
    (commands/cursorLineUp view)
    ;; Scroll viewport up
    (set! (.-scrollTop scroll-dom) (- (.-scrollTop scroll-dom) line-height))))

(defn custom-cursor-viewport-down
  "Move cursor down one line and scroll viewport down"
  [view]
  (let [line-height 20 ; Fixed line height approximation
        scroll-dom ^js (.-scrollDOM view)]
    ;; Move cursor down
    (commands/cursorLineDown view)
    ;; Scroll viewport down
    (set! (.-scrollTop scroll-dom) (+ (.-scrollTop scroll-dom) line-height))))

(defn custom-center-caret
  "Center caret position in viewport"
  [view]
  (try
    (let [state (.-state view)
          doc (.-doc state)
          scroll-dom ^js (.-scrollDOM view)
          viewport-height (.-clientHeight scroll-dom)
          scroll-top (.-scrollTop scroll-dom)
          ;; Find the top and bottom visible positions using coordinate calculations
          ;; similar to how custom-center-line works
          viewport-middle-y (+ scroll-top (/ viewport-height 2))
          ;; Find what line is at the middle of the viewport. We'll iterate through lines to
          ;; find which one is at the middle pixel position
          total-lines (.-lines doc)
          middle-line-num
            (loop [line-num 1]
              (if (>= line-num total-lines)
                total-lines
                (let [line-obj (.line ^js doc line-num)
                      line-start-pos (.-from line-obj)
                      coords ^js (.coordsAtPos ^js view line-start-pos)]
                  (if coords
                    (let [line-y (+ (.-top coords) scroll-top)]
                      (if (>= line-y viewport-middle-y) line-num (recur (inc line-num))))
                    (recur (inc line-num))))))
          ;; Get position of the found middle line
          middle-line-obj (.line ^js doc middle-line-num)
          middle-line-pos (.-from middle-line-obj)
          ;; Create transaction to move cursor
          transaction
            (.update state #js {:selection #js {:anchor middle-line-pos :head middle-line-pos}})]
      (js/console.log "Centering caret - viewport-middle-y:" viewport-middle-y
                      "middle-line:" middle-line-num
                      "scroll-top:" scroll-top)
      (.dispatch view transaction))
    (catch :default e (js/console.error "Error in custom-center-caret:" e))))

(defn custom-center-line
  "Center current line in viewport"
  [view]
  (try (let [state (.-state view)
             selection (.-selection state)
             main-selection (.-main selection)
             cursor-pos (.-head main-selection)
             doc (.-doc state)
             line-info (.lineAt ^js doc cursor-pos)
             line-start (.-from line-info)
             ;; Get coordinates of the current line
             coords ^js (.coordsAtPos ^js view line-start)
             scroll-dom ^js (.-scrollDOM view)
             scroll-top (.-scrollTop scroll-dom)
             viewport-height (.-clientHeight scroll-dom)]
         (when coords
           (let [;; Calculate absolute line position
                 line-top (.-top coords)
                 absolute-line-top (+ line-top scroll-top)
                 ;; Calculate target scroll to center the line
                 target-scroll (- absolute-line-top (/ viewport-height 2))]
             (js/console.log "Centering line - line-top:" line-top "target-scroll:" target-scroll)
             (.scrollTo scroll-dom #js {:top (max 0 target-scroll) :behavior "smooth"}))))
       (catch :default e (js/console.error "Error in custom-center-line:" e))))

(defn get-modifiers
  "Extract modifier keys from event"
  [e]
  (let [modifiers #{}]
    (cond-> modifiers
      (.-altKey e) (conj :alt)
      (.-metaKey e) (conj :meta)
      (.-ctrlKey e) (conj :ctrl)
      (.-shiftKey e) (conj :shift))))

(defn create-editor
  "Create CodeMirror 6 editor with cljs-text-editor-style keyboard handling"
  [element config]
  (let [doc (or (:doc config) "")
        ;; Create custom theme with wheat-friendly colors throughout
        custom-theme
          (.theme EditorView
                  #js {"&" #js {:height "90vh"
                                :minHeight "500px"
                                :backgroundColor "wheat"
                                :border "1px solid #ddd"}
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
                     #js [basicSetup (markdown) custom-theme line-wrapping]
                     #js [basicSetup custom-theme line-wrapping])
        ;; Create editor state
        state (.create EditorState #js {:doc doc :extensions extensions})
        ;; Create editor view
        view (new EditorView #js {:state state :parent element})]
    ;; Store reference for later access
    (aset element "__codemirror" view)
    ;; Add cljs-text-editor-style keydown handler that prevents ALL defaults
    (.addEventListener
      element
      "keydown"
      (fn [e]
        (let [code (.-code e)
              modifiers (get-modifiers e)
              key #{code modifiers}
              command (key-commands key)]
          ;; Only prevent default for our custom commands
          (if command
            (do (.preventDefault e)
                (.stopPropagation e)
                (js/console.log "Executing command for key:" (str key))
                ;; Handle both function commands and custom keywords
                (cond (= command :custom-copy) (custom-copy view)
                      (= command :custom-paste) (custom-paste view)
                      (= command :custom-cut) (custom-cut view)
                      (= command :custom-new-line-below) (custom-new-line-below view)
                      (= command :custom-new-line-above) (custom-new-line-above view)
                      (= command :custom-scroll-up) (custom-scroll-up view)
                      (= command :custom-scroll-down) (custom-scroll-down view)
                      (= command :custom-cursor-viewport-up) (custom-cursor-viewport-up view)
                      (= command :custom-cursor-viewport-down) (custom-cursor-viewport-down view)
                      (= command :custom-center-caret) (custom-center-caret view)
                      (= command :custom-center-line) (custom-center-line view)
                      (fn? command) (command view)
                      :else (js/console.warn "Unknown command:" command)))
            ;; For non-custom keys, allow normal behavior
            true)))
      true)
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
