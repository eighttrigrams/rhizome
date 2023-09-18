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
                   selected-issue
                   selected-context]} @*state]
       (.stopPropagation e)
       (when (= code "Enter")
         (.preventDefault e)
         (cond
           (and ctrl-pressed? 
                meta-pressed? 
                alt-pressed?
                (= :issues active-search)
                selected-context
                (not selected-issue))
           (actions/store-current-view! *state {:title (.-value (get-title-el))})
           (and (= :contexts active-search)
                shift-pressed?
                selected-issue)
           (actions/select-first-context! *state true)
           (or (and shift-pressed?
                    (= :contexts active-search))
               (and (= :contexts active-search)
                    (= 0 (count (:contexts @*state)))))
           (do
             (actions/new-context! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           (and (= :issues active-search)
                (not (:enter-pressed? @*state))
                (or (and shift-pressed?
                         (not (:search-globally? @*state))
                         selected-context
                         (not selected-issue))
                    (and (not (:search-globally? @*state))
                         selected-context
                         (not selected-issue)
                         (= 0 (count (:issues @*state)))))
                (issue-creation-permitted? @*state))
           (do
             (swap! *state assoc :enter-pressed? true)
             (actions/new-issue! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           #_(swap! *state dissoc nil)
           (= :contexts active-search)
           (actions/select-first-context! *state false)
           (= :issues active-search)
           (actions/select-first-issue! *state)))
       (when (and alt-pressed?
                  selected-issue
                  (= "KeyA" code))
         (.preventDefault e)
         (actions/link-with-global-search! *state))
       (when (and (= code "KeyI")
                  alt-pressed?)
         (.preventDefault e)
         (set! (.-value (get-title-el)) "")
         (actions/start-global-search! *state))
       (when (and (= code "KeyC")
                  alt-pressed?)
         (swap! *state dissoc :search-globally? :q :active-search)
         (actions/start-context-search *state))
       (when (and (= code "KeyA")
                  (not (:selected-issue @*state))
                  selected-context
                  alt-pressed?)
         (.preventDefault e)
         (set! (.-value (get-title-el)) "")
         (actions/link-issue-to-selected-context! *state))
       (when (and (= code "KeyQ")
                  alt-pressed?
                  selected-issue)
         (swap! *state assoc :active-search :contexts)
         (actions/start-linking-context *state))
       (when (= code "Escape")
         (cond (and alt-pressed? 
                    (common/something-to-deselect? *state))
               (actions/deselect-secondary-contexts! *state)
               (or (and (:search-globally? @*state)
                        selected-context)
                   (and (not selected-issue)
                        (not selected-context))
                   selected-issue 
                   selected-context)
               (actions/quit-search! *state)))))))
