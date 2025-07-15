(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? _e]
     (let [{:keys [selected-context
                   issue-view?]} @*state]
       (cond (= "Escape" code)
             (cond (and (:active-search @*state)
                        (not alt-pressed?))
                   (actions/quit-search! *state)
                   (and (not alt-pressed?) issue-view?)
                   (actions/exit-issue-view! *state)
                   (and alt-pressed? (common/something-to-deselect? *state))
                   (actions/deselect-secondary-contexts! *state)
                   (and (not alt-pressed?) selected-context)
                   (actions/deselect-context! *state))
             (not (:active-search @*state))
             (cond 
               (and (= "KeyG" code) ctrl-pressed? shift-pressed?)
               (actions/flip-privacy! *state)
               (and selected-context (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-context :issue-view? false))
               (and selected-context (= "Delete" code))
               (actions/delete-context! *state)
               (and alt-pressed? (= "KeyI" code))
               (actions/start-global-search! *state)
               (and alt-pressed?
                    (= "KeyU" code)
                    selected-context)
               (actions/upgrade-issue-to-context! *state)
               (and alt-pressed? 
                    (= "KeyT" code)
                    selected-context)
               (actions/unlink-selected-issue-from-selected-context *state)
               (and alt-pressed? 
                    (= "KeyB" code))
               (actions/select-last-context! *state)
               (= "KeyI" code)
               (swap! *state #(assoc % :active-search :issues))
               (and selected-context
                    (= "KeyA" code)
                    (not (-> @*state :selected-context :data :views :current :secondary-contexts-inverted)) 
                    (not (-> @*state :selected-context :data :views :current :secondary-contexts-unassigned-selected)))
               (actions/link-issue-to-selected-context! *state)
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
               (swap! *state #(assoc % :modal :description :issue-view? true))
               (and selected-context
                    (= "KeyS" code))
               (actions/cycle-search-mode! *state)
               (and selected-context
                    (not issue-view?)
                    (= "KeyF" code))
               (actions/enter-issue-view! *state)
               (and issue-view?
                    (= "KeyF" code))
               (actions/exit-issue-view! *state)))))))
