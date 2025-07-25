(ns ui.main.input.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*] :as common]))

;; TODO shouldn't be part of key-handler
(defn get-title-el []
  (.getElementById js/document "search-input"))

(defn- item-creation-permitted? 
  [{{{{{:keys [secondary-contexts-unassigned-selected
               search-mode]} :current} :views} :data} :selected-context}]
  (and (not secondary-contexts-unassigned-selected)
       (or (nil? search-mode)
           (= 0 search-mode))))

;; TODO replace a couple of whens with a cond
(defn handle-keys [*state]
  (handle-keys*
   (fn [code ctrl-pressed? meta-pressed? alt-pressed? shift-pressed? e]
     (let [{:keys [active-search
                   selected-context
                   link-item
                   link-context]} @*state
           in-notes-mode? (-> *state deref :selected-context :data :views :current :notes-mode)]
       (.stopPropagation e)
       (when (= code "Enter")
         (.preventDefault e)
         (cond
           (and ctrl-pressed? 
                meta-pressed? 
                alt-pressed?
                (= :items active-search)
                selected-context)
           (actions/store-current-view! *state {:title (.-value (get-title-el))})
           (and
            (not (:link-context @*state))
            (not (:selected-context @*state))
            (= :contexts active-search)
            (or shift-pressed?
                (= 0 (count (:contexts @*state)))))
           (do
             (actions/new-context! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           (and (= :items active-search)
                (not (:enter-pressed? @*state))
                selected-context
                (or (and shift-pressed? (not alt-pressed?))
                    (= 0 (count (:items @*state)))
                    in-notes-mode?)
                (item-creation-permitted? @*state))
           (do
             (swap! *state assoc :enter-pressed? true)
             (actions/new-item! *state {:title (.-value (get-title-el))})
             (set! (.-value (get-title-el)) ""))
           #_(swap! *state dissoc nil)
           (= :contexts active-search)
           (actions/select-first-context! *state shift-pressed? alt-pressed?)
           (= :items active-search)
           (actions/select-first-item! *state shift-pressed? alt-pressed?)))
       (when (and (or alt-pressed? (and shift-pressed? link-context))
                  (= "Digit2" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-second-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-context))
                  (= "Digit3" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-third-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-context))
                  (= "Digit4" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-fourth-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-context))
                  (= "Digit5" code)
                  (= :contexts active-search))
         (.preventDefault e)
         (actions/select-fifth-context! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-item))
                  (= "Digit2" code)
                  (= :items active-search))
         (.preventDefault e)
         (actions/select-second-item! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-item))
                  (= "Digit3" code)
                  (= :items active-search))
         (.preventDefault e)
         (actions/select-third-item! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-item))
                  (= "Digit4" code)
                  (= :items active-search))
         (.preventDefault e)
         (actions/select-fourth-item! *state shift-pressed? alt-pressed?))
       (when (and (or alt-pressed? (and shift-pressed? link-item))
                  (= "Digit5" code)
                  (= :items active-search))
         (.preventDefault e)
         (actions/select-fifth-item! *state shift-pressed? alt-pressed?))
       (when (and (= "KeyC" code)
                  alt-pressed?)
         (swap! *state dissoc :q :active-search)
         (actions/start-context-search *state))
       (when (and (= "KeyA" code)
                  selected-context
                  alt-pressed?)
         (.preventDefault e)
         (when-not in-notes-mode?
           (set! (.-value (get-title-el)) "")
           (actions/link-item-to-selected-context! *state)))
       (when (and (= "KeyQ" code)
                  alt-pressed?)
         (swap! *state assoc :active-search :contexts)
         (actions/start-linking-context *state))
       (when (= code "Escape")
         (cond (and alt-pressed? 
                    (common/something-to-deselect? *state))
               (actions/deselect-secondary-contexts! *state)
               (or (not selected-context)
                   selected-context)
               (actions/quit-search! *state)))))))
