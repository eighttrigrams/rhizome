(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset! 
                                       fetch-and-reset-with-method!
                                       fetch-and-reset-with-method-2!]]
            api
            [goog.async.Debouncer]
            [ui.main.rhs.modifiers :as modifiers]))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn quit-search! [*state]
  (cond
    (= :contexts (:active-search @*state))
    (fetch-and-reset! *state (-> @*state
                                 (assoc :active-search :issues)
                                 (dissoc :preview-issue 
                                         :link-issue 
                                         :q))) 
    (= :issues (:active-search @*state))
    (fetch-and-reset! *state (-> @*state 
                                 (dissoc :preview-issue 
                                         :active-search
                                         :link-issue 
                                         :q)))))

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

(defn new-issue! [*state issue]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :issues)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-issue issue))

(defn new-context! [*state context]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :issues)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-context context))

(defn- select-item! [*state context select-as-issue?]
  (reset! *state (assoc @*state
                       :active-search (when-not select-as-issue? :issues)
                       :issue-view? select-as-issue?
                       :old-selected-context (:selected-context @*state)
                       ;; For a snappy response in the UI, set :selected-issue immediately.
                       ;; The subsequent call to fetch-and-reset! then
                       ;; will fetch and replace it, thereby filling in the related issues.
                       :selected-context context))
  (fetch-and-reset-with-method-2! 
   *state 
   api/fetch-context
   [context select-as-issue?]))

(defn select-last-context! [*state]
  (fetch-and-reset-with-method-2! 
   *state 
   api/select-last-context))

(defn deselect-context! [*state]
  (fetch-and-reset-with-method-2! *state api/deselect-context))

(defn delete-item! [*state idx]
  (when (js/window.confirm "Delete this item?")
    (fetch-and-reset-with-method-2! *state api/delete-item idx)))

(defn unlink-item! [*state idx]
  (when (js/window.confirm "Unlink this item?")
    (fetch-and-reset-with-method-2! *state api/unlink-item idx)))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (fetch-and-reset-with-method-2! *state api/delete-context (:selected-context @*state))))

(defn select-context!
  ([*state context] (select-context! *state context false false))
  ([*state context shift-pressed? alt-pressed?]
   (if (true? (:link-context @*state))
     (fetch-and-reset-with-method! *state @*state api/link-selected-context-to-context context shift-pressed? alt-pressed?)
     (select-item! *state context false))))

(defn deselect-secondary-contexts! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/deselect-secondary-contexts))

(defn select-issue!
  ([*state issue] (select-issue! *state issue false false))
  ([*state issue shift-pressed? alt-pressed?]
   (if (:link-issue @*state)
     (fetch-and-reset-with-method! *state
                                   @*state
                                   api/finish-linking-issue 
                                   (:id issue)
                                   shift-pressed? 
                                   alt-pressed?)
     (select-item! *state issue true))))

(defn select-first-context! [*state shift-pressed? alt-pressed?]
  (when (seq (:contexts @*state))
    (select-context! *state (first (:contexts @*state)) shift-pressed? alt-pressed?)))

(defn- select-nth-context! [*state n shift-pressed? alt-pressed?]
  (when (and (seq (:contexts @*state))
             (> (count (:contexts @*state)) n))
    (select-context! *state 
                     (nth (:contexts @*state) n)
                     shift-pressed?
                     alt-pressed?)))

(defn select-second-context! [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 1 shift-pressed? alt-pressed?))

(defn select-third-context! [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 2 shift-pressed? alt-pressed?))

(defn select-fourth-context! [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 3 shift-pressed? alt-pressed?))

(defn select-fifth-context! [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 4 shift-pressed? alt-pressed?))

(defn reprioritize-issue [*state issue]
  (fetch-and-reset-with-method! *state
                                @*state 
                                api/reprioritize-issue 
                                issue))

(defn select-first-issue! [*state shift-pressed? alt-pressed?]
  (when (seq (:issues @*state))
    (select-issue! *state (first (:issues @*state)) shift-pressed? alt-pressed?)))

(defn- select-nth-issue! [*state n shift-pressed? alt-pressed?]
  (when (and (seq (:issues @*state))
             (> (count (:issues @*state)) n))
    (select-issue! *state (nth (:issues @*state) n) shift-pressed? alt-pressed?)))

(defn select-second-issue! [*state shift-pressed? alt-pressed?]
  (select-nth-issue! *state 1 shift-pressed? alt-pressed?))

(defn select-third-issue! [*state shift-pressed? alt-pressed?]
  (select-nth-issue! *state 2 shift-pressed? alt-pressed?))

(defn select-fourth-issue! [*state shift-pressed? alt-pressed?]
  (select-nth-issue! *state 3 shift-pressed? alt-pressed?))

(defn select-fifth-issue! [*state shift-pressed? alt-pressed?]
  (select-nth-issue! *state 4 shift-pressed? alt-pressed?))

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

(defn link-issue-to-selected-context! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-issue-to-selected-context)))

(defn search! [*state]
  (fetch-and-reset! *state @*state))

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

(defn cycle-search-mode! [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-search-mode))

(defn enter-issue-view! [*state]
  (swap! *state assoc :issue-view? true))

(defn exit-issue-view! [*state]
  (swap! *state assoc :issue-view? false))

(defn fetch-issue-description! [*state issue]
  (fetch-and-reset-with-method! *state @*state api/fetch-issue-description issue :dont-reset-preview-issue))
