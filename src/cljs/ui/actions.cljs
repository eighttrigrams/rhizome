(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset! 
                                       fetch-and-reset-with-method!]]
            api
            [goog.async.Debouncer]))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn- exec-cmd 
  ([*state cmd] (exec-cmd *state cmd nil))
  ([*state cmd arg]
   (fetch-and-reset! *state (assoc @*state :cmd cmd :arg arg))))

(defn quit-search! [*state]
  (cond
    (= :contexts (:active-search @*state))
    (fetch-and-reset! *state (-> @*state
                                 (assoc :active-search :issues)
                                 (dissoc :preview-issue 
                                         :search-globally? 
                                         :link-issue 
                                         :q))) 
    (= :issues (:active-search @*state))
    (fetch-and-reset! *state (-> @*state 
                                 (dissoc :preview-issue 
                                         :active-search
                                         :search-globally?
                                         :link-issue 
                                         :q)))))

(defn deselect-context! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :deselect-context)))

(defn deselect-issue! [*state]
  (fetch-and-reset! *state (dissoc @*state :selected-issue :preview-issue :q))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn load-stored-context [*state idx]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/load-stored-context
                                idx))

(defn remove-stored-context [*state idx]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/remove-stored-context
                                idx))

(defn store-current-view! [*state item]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal)
                                api/store-current-view
                                item))

(defn new-issue! [*state issue split-short-title?]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal)
                                api/insert-issue
                                issue
                                split-short-title?))

(defn new-context! [*state context]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd :insert-context)
                               (assoc :arg context))))

(defn select-context! 
  ([*state context] (select-context! *state context false))
  ([*state context suppress-reset-issue]
   (if (true? (:link-context @*state))
     (fetch-and-reset! *state (assoc @*state :cmd :link-context :arg context))
     (do
       ;; For a snappy response in the UI; see below
       (swap! *state assoc :selected-context context)
       (fetch-and-reset-with-method! 
        *state 
        @*state
        api/fetch-context
        [context suppress-reset-issue])))))

(defn select-first-context! [*state suppress-reset-issue]
  (when (seq (:contexts @*state))
    (select-context! *state 
                     (first (:contexts @*state)) 
                     suppress-reset-issue)))

(defn select-issue! 
  ([*state issue] (select-issue! *state issue false))
  ([*state issue skip-select?]
   (if (:link-issue @*state)
     (fetch-and-reset-with-method! *state
                                   @*state
                                   api/finish-linking-issue 
                                   (:id issue))
     (do 
       (when-not skip-select?
         ;; For a snappy response in the UI, set :selected-issue immediately.
         ;; The subsequent call to fetch-and-reset! then
         ;; will fetch and replace it, thereby filling in the related issues.
         (swap! *state assoc :selected-issue issue))
       (fetch-and-reset-with-method! *state
                                     @*state 
                                     api/select-issue 
                                     issue 
                                     skip-select?)))))

(defn select-first-issue! [*state]
  (when (seq (:issues @*state))
    (select-issue! *state (first (:issues @*state)))))

(defn start-context-search [*state]
  (fetch-and-reset! *state 
                    (assoc @*state
                           :cmd :start-context-search
                           :active-search :contexts
                           ;; TODO maybe move those into repository; also add this to for example 'i' and other modes
                           :link-issue false
                           :link-context false)))

(defn start-linking-context [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                           :cmd :start-linking-selected-issue-to-context
                           :active-search :contexts
                           :link-context true)))

;; TODO this should be moved to the backend
(defn unlink-selected-issue-from-selected-context [*state]
  (let [selected-context-id (:id (:selected-context @*state))
        selected-issue (:selected-issue @*state)
        selected-issue (update selected-issue :contexts #(dissoc % selected-context-id))
        issue-contexts-ids (keys (:contexts selected-issue))]
    (when issue-contexts-ids
      (fetch-and-reset! *state
                        (-> @*state
                            (assoc :cmd :update-issue
                                   :arg {:issue selected-issue
                                         :issue-contexts issue-contexts-ids
                                         :deselect-issue? true}))))))

(defn start-global-search! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :start-global-search)))

(defn link-with-global-search! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-with-global-search)))

(defn link-with-local-search! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-with-local-search)))

(defn link-issue-to-selected-context! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-issue-to-selected-context)))

(defn search! [*state]
  (fetch-and-reset! *state @*state))

(defn deselect-secondary-contexts! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/deselect-secondary-contexts))

(defn change-secondary-contexts-selection! [*state]
  (fetch-and-reset-with-method! *state 
                                @*state 
                                api/change-secondary-contexts-selection))

(defn change-secondary-contexts-unassigned-selected! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/change-secondary-contexts-unassigned-selected))

(defn change-secondary-contexts-inverted [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/change-secondary-contexts-inverted))

(defn cycle-events-view! [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-events-view))

(defn cycle-search-mode! [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-search-mode))

(defn cycle-notes-mode! [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-notes-mode))

(defn flip-privacy! [*state]
  (fetch-and-reset-with-method! *state @*state api/flip-privacy))

(defn delete-selected-issue! [*state]
  (when (js/window.confirm "Delete currently selected issue?")
    (fetch-and-reset-with-method! *state @*state api/delete-selected-issue)))

(defn delete-issue! [*state idx]
  (when (js/window.confirm "Delete this issue?")
    (fetch-and-reset-with-method! *state @*state api/delete-issue idx)))

(defn split-issue! [*state]
  (when (js/window.confirm "Split currently selected issue?")
    (exec-cmd *state :split-issue (:selected-issue @*state))))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (when (js/window.confirm "Sure?")
      (when (js/window.confirm "Really?")
        (exec-cmd *state :delete-context (:selected-context @*state))))))
