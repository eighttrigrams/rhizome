(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset! 
                                       fetch-and-reset-with-method!
                                       fetch-and-reset-with-method-2!]]
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

(defn new-issue! [*state issue alternative-behaviour?]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal)
                                api/insert-issue
                                issue
                                alternative-behaviour?))

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
       
       (fetch-and-reset-with-method-2! 
        *state 
        *state
        api/fetch-context
        [context suppress-reset-issue])))))

(defn select-first-context! [*state suppress-reset-issue]
  (when (seq (:contexts @*state))
    (select-context! *state 
                     (first (:contexts @*state)) 
                     suppress-reset-issue)))

(defn- select-nth-context! [*state suppress-reset-issue n]
  (when (and (seq (:contexts @*state))
             (> (count (:contexts @*state)) n))
    (select-context! *state 
                     (nth (:contexts @*state) n) 
                     suppress-reset-issue)))

(defn select-second-context! [*state suppress-reset-issue]
  (select-nth-context! *state suppress-reset-issue 1))

(defn select-third-context! [*state suppress-reset-issue]
  (select-nth-context! *state suppress-reset-issue 2))

(defn select-fourth-context! [*state suppress-reset-issue]
  (select-nth-context! *state suppress-reset-issue 3))

(defn select-fifth-context! [*state suppress-reset-issue]
  (select-nth-context! *state suppress-reset-issue 4))

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
         (swap! *state assoc :selected-context issue))
       (fetch-and-reset-with-method! *state
                                     @*state 
                                     api/fetch-context 
                                     [issue false])))))

(defn select-first-issue! [*state]
  (when (seq (:issues @*state))
    (select-issue! *state (first (:issues @*state)))))

(defn- select-nth-issue! [*state n]
  (when (and (seq (:issues @*state))
             (> (count (:issues @*state)) n))
    (select-issue! *state (nth (:issues @*state) n))))

(defn select-second-issue! [*state]
  (select-nth-issue! *state 1))

(defn select-third-issue! [*state]
  (select-nth-issue! *state 2))

(defn select-fourth-issue! [*state]
  (select-nth-issue! *state 3))

(defn select-fifth-issue! [*state]
  (select-nth-issue! *state 4))

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

(defn unlink-selected-issue-from-selected-context [*state]
  (fetch-and-reset-with-method! *state @*state api/unlink-selected-item-from-container))

(defn upgrade-issue-to-context! [*state]
  (fetch-and-reset-with-method! *state @*state api/upgrade-issue-to-context))

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

(defn show-events! [*state]
  (fetch-and-reset-with-method! *state @*state api/show-events))

(defn show-past-events! [*state]
  (fetch-and-reset-with-method! *state @*state api/show-past-events))

(defn deselect-events! [*state]
  (fetch-and-reset-with-method! *state @*state api/deselect-events))

(defn cycle-search-mode! [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-search-mode))

(defn show-context-as-issue! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/select-issue
                                (:selected-context @*state)
                                false))

(defn show-context-as-context-again! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/fetch-context
                                [(:selected-context @*state) false]))

(defn flip-privacy! [*state]
  (fetch-and-reset-with-method! *state @*state api/flip-privacy))

(defn delete-selected-issue! [*state]
  (when (js/window.confirm "Delete currently selected issue?")
    (fetch-and-reset-with-method! *state @*state api/delete-selected-issue)))

(defn delete-issue! [*state idx]
  (when (js/window.confirm "Delete this issue?")
    (fetch-and-reset-with-method! *state @*state api/delete-issue idx)))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (fetch-and-reset-with-method! *state @*state api/delete-context (:selected-context @*state))))
