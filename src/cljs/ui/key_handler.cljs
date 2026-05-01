(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.recording-mode :as recording-mode]
            [ui.danger-mode :as danger-mode]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

(defn handle-keys
  [*state]
  (handle-keys*
    (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? _e]
      (let [{:keys [selected-item item-view?]} @*state]
        (cond (and alt-pressed? shift-pressed? (= "KeyW" code))
                (recording-mode/toggle! *state)
              (and alt-pressed? shift-pressed? (= "KeyD" code))
                (danger-mode/toggle! *state)
              (= "Escape" code)
                (cond (and (:active-search @*state) (not alt-pressed?)) (actions/quit-search!
                                                                          *state)
                      (and (not alt-pressed?) item-view?) (actions/exit-item-view! *state)
                      (and alt-pressed? (common/something-to-deselect? *state))
                        (actions/deselect-secondary-contexts! *state)
                      (and (not alt-pressed?) selected-item) (actions/deselect-context! *state))
              (not (:active-search @*state))
                (cond
                  (and selected-item (= "KeyE" code)) (swap! *state #(assoc %
                                                                       :modal :edit-context
                                                                       :item-view? false))
                  (and selected-item (= "Delete" code)) (actions/delete-context! *state)
                  (and alt-pressed? (= "KeyU" code) selected-item) (actions/upgrade-item-to-context!
                                                                     *state)
                  (and alt-pressed? (= "KeyT" code) selected-item)
                    (actions/unlink-selected-item-from-selected-context *state)
                  (and alt-pressed? (= "KeyB" code)) (actions/select-last-context! *state)
                  (= "KeyI" code) (swap! *state #(assoc % :active-search :items))
                  (and selected-item
                       (= "KeyA" code)
                       (not (-> @*state
                                :selected-item
                                :data
                                :views
                                :current
                                :secondary-contexts-inverted))
                       (not (-> @*state
                                :selected-item
                                :data
                                :views
                                :current
                                :secondary-contexts-unassigned-selected)))
                    (actions/link-item-to-selected-item! *state)
                  (and (= "KeyC" code) (not meta-pressed?) (not ctrl-pressed?) (not shift-pressed?))
                    (actions/start-context-search *state)
                  (and (= "KeyQ" code)
                       (not meta-pressed?)
                       (not ctrl-pressed?)
                       (not shift-pressed?)
                       selected-item)
                    (actions/start-linking-context *state)
                  (and selected-item (= "KeyD" code) alt-pressed?) (actions/edit-item-in-obsidian!
                                                                     *state)
                  (and selected-item (= "KeyD" code) (not alt-pressed?)) (swap! *state
                                                                           #(assoc %
                                                                              :modal :description
                                                                              :item-view? true))
                  (and selected-item (= "KeyS" code)) (actions/cycle-search-mode! *state)
                  (and selected-item (not item-view?) (= "KeyF" code)) (actions/enter-item-view!
                                                                         *state)
                  (and item-view? (= "KeyF" code)) (actions/exit-item-view! *state)))))))
