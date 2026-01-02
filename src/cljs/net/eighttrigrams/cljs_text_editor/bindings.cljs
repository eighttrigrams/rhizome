(ns net.eighttrigrams.cljs-text-editor.bindings)

(def commands
  {#{"KeyY" #{:ctrl}} :restore
   #{"KeyJ" #{:alt}} :caret-left
   #{"KeyL" #{:alt}} :caret-right
   #{"KeyI" #{:alt}} :caret-up
   #{"KeyK" #{:alt}} :caret-down
   #{"KeyL" #{:shift :alt}} :caret-right-with-selection
   #{"KeyJ" #{:shift :alt}} :caret-left-with-selection
   #{"KeyK" #{:shift :alt}} :caret-down-with-selection
   #{"KeyI" #{:shift :alt}} :caret-up-with-selection
   #{"KeyL" #{:meta}} :word-part-right
   #{"KeyJ" #{:meta}} :word-part-left
   #{"KeyL" #{:shift :meta}} :move-selection-wordwise-right
   #{"KeyJ" #{:shift :meta}} :move-selection-wordwise-left
   #{"KeyL" #{:ctrl}} :sentence-right
   #{"KeyJ" #{:ctrl}} :sentence-left
   #{"KeyL" #{:shift :ctrl}} :sentence-right-with-selection
   #{"KeyJ" #{:shift :ctrl}} :sentence-left-with-selection
   #{"Backspace" #{} :selection-present} :delete-with-selection-present
   #{"Backspace" #{:shift} :selection-present} :delete-with-selection-present
   #{"Backspace" #{}} :delete
   #{"Equal" #{}} :delete-forward
   #{"Backspace" #{:meta}} :delete-wordwise-backward
   #{"Equal" #{:meta}} :delete-wordwise-forward
   #{"Backspace" #{:ctrl}} :delete-sentence-wise-backward
   #{"Equal" #{:ctrl}} :delete-sentence-wise-forward
   #{"Enter" #{:shift}} :shift-enter
   #{"Enter" #{:alt}} :alt-enter
   #{"INSERT" #{:ctrl}} :insert
   #{"KeyV" #{:ctrl}} nil
   #{"KeyX" #{:ctrl}} nil
   #{"KeyC" #{:ctrl}} nil
   #{"Tab" #{}} :insert-tab
   #{"Tab" #{:shift}} :nop})
