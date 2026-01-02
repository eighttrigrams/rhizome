(ns net.eighttrigrams.cljs-text-editor.editor
  (:require [net.eighttrigrams.cljs-text-editor.helpers :as helpers]
            [net.eighttrigrams.cljs-text-editor.machine :as machine]
            [net.eighttrigrams.cljs-text-editor.bindings :as bindings]
            [net.eighttrigrams.cljs-text-editor.bindings-resolver :as bindings-resolver]
            [net.eighttrigrams.cljs-text-editor.time-machine :as time-machine]))

(defn set-values!
  [el {selection-start :selection-start selection-end :selection-end value :value}]
  (set! (.-value el) value)
  (set! (.-selectionStart el) selection-start)
  (set! (.-selectionEnd el) selection-end))

(defn set-modifiers!
  [e b modifiers]
  (let [code (case (.-code e)
               "ControlLeft" :ctrl
               "ControlRight" :ctrl
               "ShiftLeft" :shift
               "AltLeft" :alt
               "MetaLeft" :meta
               nil)]
    (when code (swap! modifiers (if b conj disj) code))))

(defn construct-state
  [el position-in-line:atom]
  (let [selection-start (.-selectionStart el)
        selection-end (.-selectionEnd el)
        [pos-in-line] (helpers/cursor-position-in-line (.-value el) selection-start)]
    {:value (.-value el)
     :selection-start selection-start
     :selection-end selection-end
     :selection-present? (not= selection-start selection-end)
     :position-in-line (if @position-in-line:atom
                         @position-in-line:atom
                         (do (reset! position-in-line:atom pos-in-line) pos-in-line))
     :prevent-adjust-position-in-line false
     :dont-prevent-default false}))

(defn paste
  [el modifiers transform-state position-in-line:atom]
  (fn [e]
    (.preventDefault e)
    (->> (.getData (.-clipboardData e) "Text")
         (assoc (construct-state el position-in-line:atom) :clipboard-data)
         (transform-state ["INSERT" @modifiers])
         (set-values! el))))

(defn keydown
  [el modifiers transform-state position-in-line:atom debug?]
  (fn [e]
    (set-modifiers! e true modifiers)
    (when debug? (prn "code" (.-code e) "modifiers" @modifiers))
    (let [new-state (transform-state [(.-code e) @modifiers]
                                     (construct-state el position-in-line:atom))]
      (set-values! el new-state)
      (reset! position-in-line:atom (:position-in-line new-state))
      (when (not= (:dont-prevent-default new-state) true) (.preventDefault e)))))

(defn click
  [el position-in-line:atom]
  (fn [_e]
    (let [[position-in-line] (helpers/cursor-position-in-line (.-value el) (.-selectionStart el))]
      (reset! position-in-line:atom position-in-line))))

(defn keyup [_el modifiers] (fn [e] (set-modifiers! e false modifiers)))

(defn mouseleave [_el modifiers] (fn [_e] (reset! modifiers #{})))

(defn- get-bindings
  [input-field-mode?]
  (if-not input-field-mode?
    bindings/commands
    (->> bindings/commands
         (remove (fn [[k _v]] (contains? k "Tab")))
         (into {}))))

(defn ^:export create
  [el {input-field-mode? :input-field-mode? debug? :debug?}]
  (when debug? (prn (get-bindings input-field-mode?)))
  (let [modifiers (atom #{})
        position-in-line (atom nil)
        resolve-bindings (bindings-resolver/build (get-bindings input-field-mode?))
        transform-state (-> (machine/build)
                            time-machine/build
                            resolve-bindings)]
    (.addEventListener el "paste" (paste el modifiers transform-state position-in-line))
    (.addEventListener el "keydown" (keydown el modifiers transform-state position-in-line debug?))
    (.addEventListener el "keyup" (keyup el modifiers))
    (.addEventListener el "mouseleave" (mouseleave el modifiers))
    (.addEventListener el "click" (click el position-in-line))))
