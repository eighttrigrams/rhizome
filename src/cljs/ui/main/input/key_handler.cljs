(ns ui.main.input.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*]]))

;; TODO shouldn't be part of key-handler
(defn get-title-el []
  (.getElementById js/document "search-input"))

;; TODO replace a couple of whens with a cond
(defn handle-keys [*state]
  (handle-keys*
   (fn [code _ctrl-pressed? _meta-pressed? alt-pressed? shift-pressed? e]
     (let [{:keys [active-search
                   selected-issue
                   selected-context]} @*state]
       (.stopPropagation e)
       (when (= code "Enter")
         (.preventDefault e)
         (cond
           (or (and shift-pressed?
                    (= :contexts active-search))
               (and (= :contexts active-search)
                    (= 0 (count (:contexts @*state)))))
           (do
             (actions/new-context! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           (and (= :issues active-search)
                (or (and shift-pressed?
                         (not (:search-globally? @*state))
                         selected-context
                         (not selected-issue))
                    (and (not (:search-globally? @*state))
                         selected-context
                         (not selected-issue)
                         (= 0 (count (:issues @*state))))))
           (when-not (:enter-pressed? @*state)
             (swap! *state assoc :enter-pressed? true)
             (actions/new-issue! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           #_(swap! *state dissoc nil)
           (= :contexts active-search)
           (actions/select-first-context! *state)
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
       (when (and (= code "KeyD")
                  alt-pressed?
                  selected-issue)
         (swap! *state assoc :active-search :contexts)
         (actions/start-linking-context *state))
       (when (= code "Escape")
         (cond (and alt-pressed? 
                    (seq (:selected-secondary-contexts 
                          (:data selected-context))))
               (actions/deselect-secondary-contexts! *state)
               (or (and (:search-globally? @*state)
                        selected-context)
                   (and (not selected-issue)
                        (not selected-context))
                   selected-issue 
                   selected-context)
               (actions/quit-search! *state)))))))
