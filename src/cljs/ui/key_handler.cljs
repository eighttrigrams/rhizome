(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? _e]
     (let [{:keys [selected-context
                   item-view?]} @*state]
       (cond (= "Escape" code)
             (cond (and (:active-search @*state)
                        (not alt-pressed?))
                   (actions/quit-search! *state)
                   (and (not alt-pressed?) item-view?)
                   (actions/exit-item-view! *state)
                   (and alt-pressed? (common/something-to-deselect? *state))
                   (actions/deselect-secondary-contexts! *state)
                   (and (not alt-pressed?) selected-context)
                   (actions/deselect-context! *state))
             (not (:active-search @*state))
             (cond
               (and selected-context (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-context :item-view? false))
               (and selected-context (= "Delete" code))
               (actions/delete-context! *state)
               (and alt-pressed?
                    (= "KeyU" code)
                    selected-context)
               (actions/upgrade-item-to-context! *state)
               (and alt-pressed? 
                    (= "KeyT" code)
                    selected-context)
               (actions/unlink-selected-item-from-selected-context *state)
               (and alt-pressed? 
                    (= "KeyB" code))
               (actions/select-last-context! *state)
               (= "KeyI" code)
               (swap! *state #(assoc % :active-search :items))
               (and selected-context
                    (= "KeyA" code)
                    (not (-> @*state :selected-context :data :views :current :secondary-contexts-inverted)) 
                    (not (-> @*state :selected-context :data :views :current :secondary-contexts-unassigned-selected)))
               (actions/link-item-to-selected-context! *state)
               (and (= "KeyC" code)
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    (not shift-pressed?))
               (actions/start-context-search *state)
               (and (= "KeyQ" code)
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    (not shift-pressed?)
                    selected-context)
               (actions/start-linking-context *state)
               (and selected-context
                    (= "KeyD" code))
               (swap! *state #(assoc % :modal :description :item-view? true))
               (and selected-context
                    (= "KeyS" code))
               (actions/cycle-search-mode! *state)
               (and selected-context
                    (not item-view?)
                    (= "KeyF" code))
               (actions/enter-item-view! *state)
               (and item-view?
                    (= "KeyF" code))
               (actions/exit-item-view! *state)))))))
