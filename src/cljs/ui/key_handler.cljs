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
                   (swap! *state #(dissoc % :selected-issue))
                   (seq (:selected-secondary-contexts-ids @*state))
                   (actions/deselect-secondary-contexts! *state)
                   selected-context
                   (actions/deselect-context! *state))
             (not (:active-search @*state))
             (cond
               (= "KeyV" code)
               (actions/show-events! *state)
               (and
                (or
                 selected-issue
                 selected-context)
                (= "KeyD" code))
               (swap! *state #(assoc % :modal :description))
               (and selected-issue (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-issue)) 
               (and selected-issue (= "Delete" code))
               (actions/delete-issue! *state)
               (and selected-context (= "Delete" code))
               (actions/delete-context! *state)
               (and selected-issue (= "KeyP" code))
               (actions/reprioritize-issue! *state)
               (and selected-issue (= "KeyL" code))
               (swap! *state #(assoc % :modal :link-context-issue))
               (and selected-issue (= "KeyT" code))
               (actions/mark-issue-important! *state)
               (and selected-context (= "KeyE" code))
               (swap! *state #(assoc % :modal :edit-context))
               (and shift-pressed? (= "KeyI" code))
               (actions/start-global-search! *state) 
               (= "KeyI" code)
               (swap! *state #(assoc % :active-search :issues :search-globally? false))
               (and (= "KeyC" code) 
                    (not meta-pressed?)
                    (not ctrl-pressed?)
                    (not alt-pressed?)
                    (not (:show-events? @*state)))
               (swap! *state #(assoc % :active-search :contexts))
               (and selected-context 
                    (not selected-issue)
                    (= "KeyS" code))
               (actions/cycle-search-mode! *state)
               (and selected-context (= "KeyN" code))
               (swap! *state #(assoc % :modal :new-issue))
               (= "KeyN" code)
               (swap! *state #(assoc % :modal :new-context))))))))
