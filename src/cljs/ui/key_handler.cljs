(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*]]))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? _e]
     (let [{:keys [selected-issue
                   selected-context]} @*state]
       (cond (= "Escape" code)
             (cond (:active-search @*state)
                   (actions/quit-search! *state)
                   (:show-events? @*state)
                   (actions/exit-events-view! *state)
                   selected-issue
                   (actions/deselect-issue! *state)
                   (and alt-pressed? (seq (:selected-secondary-contexts (:data (:selected-context @*state)))))
                   (actions/deselect-secondary-contexts! *state)
                   selected-context
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
               (and alt-pressed? selected-context (not selected-issue) (= "KeyD" code))
               (actions/link-context-with-global-search! *state)
               (and (= "KeyC" code)
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    (not shift-pressed?))
               (actions/start-context-search *state)
               (and (= "KeyD" code)
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
