(ns ui.codemirror
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/commands" :as commands]
            ["@codemirror/lang-markdown" :refer [markdown]]))

;; Direct key to command mapping
(def key-commands
  {#{"KeyJ" #{:meta}}        commands/cursorCharLeft
   #{"KeyL" #{:meta}}        commands/cursorCharRight
   #{"KeyI" #{:meta}}        commands/cursorLineUp
   #{"KeyK" #{:meta}}        commands/cursorLineDown
   #{"KeyJ" #{:alt}}         commands/cursorGroupLeft
   #{"KeyL" #{:alt}}         commands/cursorGroupRight
   #{"KeyI" #{:alt}}         commands/cursorLineUp
   #{"KeyK" #{:alt}}         commands/cursorLineDown
   #{"KeyJ" #{:ctrl}}        commands/cursorLineStart
   #{"KeyL" #{:ctrl}}        commands/cursorLineEnd
   #{"KeyJ" #{:meta :shift}} commands/selectCharLeft
   #{"KeyL" #{:meta :shift}} commands/selectCharRight
   #{"KeyI" #{:meta :shift}} commands/selectLineUp
   #{"KeyK" #{:meta :shift}} commands/selectLineDown
   #{"KeyJ" #{:alt :shift}}  commands/selectGroupLeft
   #{"KeyL" #{:alt :shift}}  commands/selectGroupRight
   #{"KeyI" #{:alt :shift}}  commands/selectLineUp
   #{"KeyK" #{:alt :shift}}  commands/selectLineDown
   #{"KeyJ" #{:ctrl :shift}} commands/selectLineStart
   #{"KeyL" #{:ctrl :shift}} commands/selectLineEnd})

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
                              (command view))
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