(ns ui.codemirror
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/commands" :as commands]
            ["@codemirror/lang-markdown" :refer [markdown]]))

;; Direct key to command mapping
(def key-commands
  {;; Basic movement
   #{"KeyJ" #{:meta}}        commands/cursorCharLeft
   #{"KeyL" #{:meta}}        commands/cursorCharRight
   #{"KeyI" #{:meta}}        commands/cursorLineUp
   #{"KeyK" #{:meta}}        commands/cursorLineDown
   #{"KeyJ" #{:alt}}         commands/cursorGroupLeft
   #{"KeyL" #{:alt}}         commands/cursorGroupRight
   #{"KeyI" #{:alt}}         commands/cursorLineUp
   #{"KeyK" #{:alt}}         commands/cursorLineDown
   #{"KeyJ" #{:ctrl}}        commands/cursorLineStart
   #{"KeyL" #{:ctrl}}        commands/cursorLineEnd
   
   ;; Selection variants
   #{"KeyJ" #{:meta :shift}} commands/selectCharLeft
   #{"KeyL" #{:meta :shift}} commands/selectCharRight
   #{"KeyI" #{:meta :shift}} commands/selectLineUp
   #{"KeyK" #{:meta :shift}} commands/selectLineDown
   #{"KeyJ" #{:alt :shift}}  commands/selectGroupLeft
   #{"KeyL" #{:alt :shift}}  commands/selectGroupRight
   #{"KeyI" #{:alt :shift}}  commands/selectLineUp
   #{"KeyK" #{:alt :shift}}  commands/selectLineDown
   #{"KeyJ" #{:ctrl :shift}} commands/selectLineStart
   #{"KeyL" #{:ctrl :shift}} commands/selectLineEnd
   
   ;; Delete operations
   #{"Quote" #{:alt}}        commands/deleteGroupForward
   #{"Equal" #{:meta}}       commands/deleteCharForward
   #{"Backspace" #{:ctrl}}   commands/deleteToLineStart
   #{"Equal" #{:ctrl}}       commands/deleteToLineEnd
   #{"Equal" #{:ctrl :meta}} commands/deleteLine
   
   ;; Line operations  
   #{"Enter" #{:shift}}      :custom-new-line-below
   #{"Enter" #{:meta}}       :custom-new-line-above
   #{"KeyI" #{:ctrl :meta}}  commands/moveLineUp
   #{"KeyK" #{:ctrl :meta}}  commands/moveLineDown
   
   ;; Select all
   #{"KeyA" #{:alt}}         commands/selectAll
   
   ;; Undo/Redo
   #{"Backquote" #{:alt}}    commands/undo
   #{"Backquote" #{:shift}}  commands/redo
   
   ;; Clipboard operations (custom implementations)
   #{"KeyC" #{:alt}}         :custom-copy
   #{"KeyV" #{:alt}}         :custom-paste
   #{"KeyX" #{:alt}}         :custom-cut})

;; Custom clipboard operations
(defn custom-copy [view]
  (let [selection (.. view -state -selection -main)]
    (when-not (= (.-from selection) (.-to selection))
      (let [text (.. view -state -doc (slice (.-from selection) (.-to selection)))]
        (.writeText js/navigator.clipboard text)))))

(defn custom-paste [view]
  (.then (.readText js/navigator.clipboard)
         (fn [text]
           (let [selection (.. view -state -selection -main)
                 transaction (.update (.-state view)
                                     #js {:changes #js {:from (.-from selection)
                                                       :to (.-to selection)
                                                       :insert text}})]
             (.dispatch view transaction)))))

(defn custom-cut [view]
  (let [selection (.. view -state -selection -main)]
    (when-not (= (.-from selection) (.-to selection))
      (let [text (.. view -state -doc (slice (.-from selection) (.-to selection)))]
        (.writeText js/navigator.clipboard text)
        (let [transaction (.update (.-state view)
                                   #js {:changes #js {:from (.-from selection)
                                                     :to (.-to selection)
                                                     :insert ""}})]
          (.dispatch view transaction))))))

(defn custom-new-line-below [view]
  "Insert a new line below current line and move cursor to it"
  (let [state (.-state view)
        cursor (.. state -selection -main -head)
        line-info (.lineAt (.-doc state) cursor)
        line-end (.-to line-info)
        transaction (.update state
                             #js {:changes #js {:from line-end
                                               :to line-end
                                               :insert "\n"}
                                  :selection #js {:anchor (inc line-end)
                                                 :head (inc line-end)}})]
    (.dispatch view transaction)))

(defn custom-new-line-above [view]
  "Insert a new line above current line and move cursor to it"
  (let [state (.-state view)
        cursor (.. state -selection -main -head)
        line-info (.lineAt (.-doc state) cursor)
        line-start (.-from line-info)
        transaction (.update state
                             #js {:changes #js {:from line-start
                                               :to line-start
                                               :insert "\n"}
                                  :selection #js {:anchor line-start
                                                 :head line-start}})]
    (.dispatch view transaction)))

(defn get-modifiers "Extract modifier keys from event"
  [e]
  (let [modifiers #{}]
    (cond-> modifiers
      (.-altKey e) (conj :alt)
      (.-metaKey e) (conj :meta)
      (.-ctrlKey e) (conj :ctrl)
      (.-shiftKey e) (conj :shift))))

(defn create-editor "Create CodeMirror 6 editor with cljs-text-editor-style keyboard handling"
  [element config]
  (let [doc (or (:doc config) "")
        
        ;; Create scrolling extension for long content
        scrolling-theme (.theme EditorView
                                #js {"&" #js {:height "90vh"
                                             :minHeight "500px"}
                                     ".cm-scroller" #js {:overflow "auto"}
                                     ".cm-content" #js {:padding "10px"}})
        
        ;; Build extensions array (minimal - no custom keymaps since we handle everything)
        extensions (if (:markdown? config)
                     #js [basicSetup (markdown) scrolling-theme]
                     #js [basicSetup scrolling-theme])
        
        ;; Create editor state
        state (.create EditorState
                       #js {:doc doc
                            :extensions extensions})
        
        ;; Create editor view
        view (new EditorView
                  #js {:state state
                       :parent element})]
    
    ;; Store reference for later access
    (aset element "__codemirror" view)
    
    ;; Add cljs-text-editor-style keydown handler that prevents ALL defaults
    (.addEventListener element "keydown" 
                      (fn [e]
                        (let [code (.-code e)
                              modifiers (get-modifiers e)
                              key #{code modifiers}
                              command (key-commands key)]
                          
                          ;; Only prevent default for our custom commands
                          (if command
                            (do
                              (.preventDefault e)
                              (.stopPropagation e)
                              (js/console.log "Executing command for key:" (str key))
                              ;; Handle both function commands and custom keywords
                              (cond
                                (= command :custom-copy) (custom-copy view)
                                (= command :custom-paste) (custom-paste view)
                                (= command :custom-cut) (custom-cut view)
                                (= command :custom-new-line-below) (custom-new-line-below view)
                                (= command :custom-new-line-above) (custom-new-line-above view)
                                (fn? command) (command view)
                                :else (js/console.warn "Unknown command:" command)))
                            ;; For non-custom keys, allow normal behavior
                            true)))
                      true)
    
    ;; Focus if requested
    (when (:focus? config)
      (.focus view))
    
    view))

(defn get-editor-value "Get current content from CodeMirror editor"
  [view]
  (when view
    (.. view -state -doc toString)))

(defn set-editor-value "Set content in CodeMirror editor"
  [view value]
  (when view
    (let [transaction (.update (.-state view)
                               #js {:changes #js {:from 0
                                                  :to (.. view -state -doc -length)
                                                  :insert value}})]
      (.dispatch view transaction))))