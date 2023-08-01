(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset!]]
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
    (and (:active-search @*state)
         (not (:selected-issue @*state))
         (not (:selected-context @*state)))
    (fetch-and-reset! *state (-> @*state
                                 (dissoc :preview-issue 
                                         :search-globally? 
                                         :link-issue 
                                         :active-search
                                         :q)))
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

(defn new-issue! [*state issue]
  ;; TODO use exec-cmd ?
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd :insert-issue)
                               (assoc :arg issue))))

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
       (fetch-and-reset! *state
                         (assoc @*state
                                :cmd :fetch-context 
                                :arg [context suppress-reset-issue]))))))

(defn select-first-context! [*state]
  (when (seq (:contexts @*state))
    (select-context! *state (first (:contexts @*state)))))

(defn select-issue! 
  ([*state issue] (select-issue! *state issue false))
  ([*state issue skip-select?]
   (if (:link-issue @*state)
     (fetch-and-reset! *state (assoc @*state :cmd :finish-link-issue :arg (:id issue)))
     (do
       (when-not skip-select?
         ;; For a snappy response in the UI, set :selected-issue immediately.
         ;; The subsequent call to fetch-and-reset! then
         ;; will fetch and replace it, thereby filling in the related issues.
         (swap! *state assoc :selected-issue issue))
       (fetch-and-reset! *state (assoc @*state :cmd 
                                       :fetch-issue :arg [issue skip-select?]))))))

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

(defn link-context-with-global-search! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-issue-to-selected-context)))

(defn search! [*state]
  (fetch-and-reset! *state @*state))

(defn deselect-secondary-contexts! [*state]
  (exec-cmd *state :deselect-secondary-contexts))

(defn change-secondary-contexts-selection! [*state]
  (exec-cmd *state :change-secondary-contexts-selection))

(defn change-secondary-contexts-unassigned-selected! [*state]
  (exec-cmd *state :change-secondary-contexts-unassigned-selected))

(defn change-secondary-contexts-inverted! [*state]
  (exec-cmd *state :change-secondary-contexts-inverted))

(defn change-secondary-contexts-and! [*state]
  (exec-cmd *state :change-secondary-contexts-and))

(defn show-events! [*state]
  (exec-cmd *state :enter-events-view))

(defn exit-events-view! [*state]
  (exec-cmd *state :exit-events-view))

(defn cycle-search-mode! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :cycle-search-mode)))

(defn delete-issue! [*state]
  (when (js/window.confirm "Delete currently selected issue?")
    (exec-cmd *state :delete-issue (:selected-issue @*state))))

(defn split-issue! [*state]
  (when (js/window.confirm "Split currently selected issue?")
    (exec-cmd *state :split-issue (:selected-issue @*state))))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (exec-cmd *state :delete-context (:selected-context @*state))))
