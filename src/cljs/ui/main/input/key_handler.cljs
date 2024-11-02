(ns ui.main.input.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

;; TODO shouldn't be part of key-handler
(defn get-title-el []
  (.getElementById js/document "search-input"))

(defn- issue-creation-permitted? 
  [{{{{{:keys [secondary-contexts-unassigned-selected
               events-view]} :current} :views} :data} :selected-context}]
  (and (not secondary-contexts-unassigned-selected)
       (or (= 0 events-view) 
           (nil? events-view))))

;; TODO replace a couple of whens with a cond
(defn handle-keys [*state]
  (handle-keys*
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? e]
     (let [{:keys [active-search
                   selected-context]} @*state
           in-notes-mode? (-> *state deref :selected-context :data :views :current :notes-mode)]
       (.stopPropagation e)
       (when (= code "Enter")
         (.preventDefault e)
         (cond
           (and ctrl-pressed? 
                meta-pressed? 
                alt-pressed?
                (= :issues active-search)
                selected-context)
           (actions/store-current-view! *state {:title (.-value (get-title-el))})
           (or (and shift-pressed?
                    (= :contexts active-search))
               (and (= :contexts active-search)
                    (= 0 (count (:contexts @*state)))))
           (do
             (actions/new-context! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           (and (= :issues active-search)
                (not (:enter-pressed? @*state))
                (not (:search-globally? @*state))
                selected-context
                (or shift-pressed?
                    alt-pressed?
                    (= 0 (count (:issues @*state)))
                    in-notes-mode?)
                (issue-creation-permitted? @*state))
           (do
             (swap! *state assoc :enter-pressed? true)
             (actions/new-issue! *state {:title (.-value (get-title-el))}
                                 (when-not in-notes-mode?
                                   alt-pressed?))
             (set! (.-value (get-title-el)) ""))
           #_(swap! *state dissoc nil)
           (= :contexts active-search)
           (actions/select-first-context! *state shift-pressed? alt-pressed?)
           (= :issues active-search)
           (actions/select-first-issue! *state shift-pressed? alt-pressed?)))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit2" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-second-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit3" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-third-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit4" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-fourth-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit5" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-fifth-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit2" code)
                  (= :issues active-search))
         (.preventDefault e)
         (actions/select-second-issue! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit3" code)
                  (= :issues active-search))
         (.preventDefault e)
         (actions/select-third-issue! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit4" code)
                  (= :issues active-search))
         (.preventDefault e)
         (actions/select-fourth-issue! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? shift-pressed?)
                  (= "Digit5" code)
                  (= :issues active-search))
         (.preventDefault e)
         (actions/select-fifth-issue! *state shift-pressed? alt-pressed?))
       (when (and (= "KeyI" code)
                  alt-pressed?)
         (.preventDefault e)
         (set! (.-value (get-title-el)) "")
         (actions/start-global-search! *state))
       (when (and (= "KeyC" code)
                  alt-pressed?)
         (swap! *state dissoc :search-globally? :q :active-search)
         (actions/start-context-search *state))
       (when (and (= "KeyA" code)
                  (not (:selected-issue @*state))
                  selected-context
                  alt-pressed?)
         (.preventDefault e)
         (when-not in-notes-mode?
           (set! (.-value (get-title-el)) "")
           (actions/link-issue-to-selected-context! *state)))
       (when (and (= "KeyQ" code)
                  alt-pressed?)
         (swap! *state assoc :active-search :contexts)
         (actions/start-linking-context *state))
       (when (= code "Escape")
         (cond (and alt-pressed? 
                    (common/something-to-deselect? *state))
               (actions/deselect-secondary-contexts! *state)
               (or (and (:search-globally? @*state)
                        selected-context)
                   (not selected-context)
                   selected-context)
               (actions/quit-search! *state)))))))
