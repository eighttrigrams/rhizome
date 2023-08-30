(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? _e]
     (let [{:keys [selected-issue
                   selected-context]} @*state]
       (cond (= "Escape" code)
             (cond (:active-search @*state)
                   (and (not alt-pressed?) (actions/quit-search! *state))
                   (:show-events? @*state)
                   (and (not alt-pressed?)(actions/exit-events-view! *state))
                   (and (not alt-pressed?) selected-issue)
                   (actions/deselect-issue! *state)
                   (and alt-pressed? (common/something-to-deselect? *state))
                   (actions/deselect-secondary-contexts! *state)
                   (and (not alt-pressed?) selected-context)
                   (actions/deselect-context! *state))
             (not (:active-search @*state))
             (cond
               (= "KeyV" code)
               (actions/show-events! *state)
               (and selected-issue (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-issue))
               (and selected-context
                    selected-issue
                    shift-pressed?
                    alt-pressed?
                    (= "KeyS" code))
               (actions/split-issue! *state)
               (and selected-issue (= "Delete" code))
               (actions/delete-issue! *state)
               (and selected-context (= "Delete" code))
               (actions/delete-context! *state)
               (and selected-context (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-context))
               (and alt-pressed? (= "KeyI" code))
               (actions/start-global-search! *state)
               (and alt-pressed? 
                    (= "KeyT" code)
                    selected-context
                    selected-issue)
               (actions/unlink-selected-issue-from-selected-context *state)
               (= "KeyI" code)
               (swap! *state #(assoc % :active-search :issues :search-globally? false))
               (and alt-pressed? selected-issue (= "KeyA" code))
               (actions/link-with-global-search! *state)
               (and (not alt-pressed?) selected-issue (= "KeyA" code))
               (actions/link-with-local-search! *state)
               (and selected-context (not selected-issue) (= "KeyA" code))
               (actions/link-issue-to-selected-context! *state)
               (and (= "KeyC" code)
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    (not shift-pressed?))
               (actions/start-context-search *state)
               (and (= "KeyQ" code)
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    alt-pressed?
                    (not shift-pressed?)
                    selected-issue)
               (actions/start-linking-context *state)
               (and
                (or
                 selected-issue
                 selected-context)
                (= "KeyD" code))
               (swap! *state #(assoc % :modal :description))
               (and selected-context
                    (not selected-issue)
                    (= "KeyS" code))
               (actions/cycle-search-mode! *state)))))))
