(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset!]]
            api))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn- exec-cmd 
  ([*state cmd] (exec-cmd *state cmd nil))
  ([*state cmd arg]
   (fetch-and-reset! *state (assoc @*state :cmd cmd :arg arg))))

(defn quit-search! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :loading true)
                               (dissoc :preview-issue :active-search :search-globally? :link-issue)))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn deselect-context! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :deselect-context :loading true))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn deselect-issue! [*state]
  (swap! *state #(-> @*state 
                     (assoc :loading true)
                     (dissoc :selected-issue)
                     (dissoc :preview-issue)))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn select-context! 
  ([*state context] (select-context! *state context false))
  ([*state context suppress-reset-issue]
   ;; See below
   (swap! *state assoc :selected-context context)
   (if (true? (:link-context @*state))
     (fetch-and-reset! *state (assoc @*state :cmd :link-context :arg context))
     (fetch-and-reset! *state (-> @*state
                                  (assoc :cmd :fetch-context 
                                         :arg context)
                                  (#(if-not suppress-reset-issue
                                      (dissoc % :selected-issue) ;; TODO review
                                      (identity %))))))))

(defn select-issue! [*state issue]
  (cond 
    (= :issue (:link-issue @*state))
    (fetch-and-reset! *state (assoc @*state :cmd :link-issues :arg (:id issue)))
    (= :context (:link-issue @*state))
    (fetch-and-reset! *state (assoc @*state :cmd :link-issue-context :arg (:id issue)))
    :else 
    (do
      ;; For a snappy response in the UI, set :selected-issue immediately.
      ;; The subsequent call to fetch-and-reset! then
      ;; will fetch and replace it, thereby filling in the related issues.
      (swap! *state assoc :selected-issue issue)
      (fetch-and-reset! *state (assoc @*state :cmd :fetch-issue :arg issue)))))

(defn start-global-search! [*state]
  (fetch-and-reset! *state
                    (assoc @*state 
                           :active-search :issues
                           :search-globally? true)
                    ""))

(defn link-with-global-search! [*state]
  (fetch-and-reset! *state
                    (assoc @*state 
                           :active-search :issues
                           :search-globally? true
                           :link-issue :issue)
                    ""))

(defn link-with-local-search! [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                           :active-search :issues
                           :search-globally? false
                           :link-issue :issue)
                    ""))

(defn link-context-with-global-search! [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                           :active-search :issues
                           :search-globally? true
                           :link-issue :context)
                    ""))

(defn search! [*state value]
  (fetch-and-reset! *state @*state value))

(defn deselect-secondary-contexts! [*state]
  (exec-cmd *state :deselect-secondary-contexts))

(defn change-secondary-contexts-selection! [*state]
  (exec-cmd *state :change-secondary-contexts-selection))

(defn change-secondary-contexts-unassigned-selected! [*state]
  (exec-cmd *state :change-secondary-contexts-unassigned-selected))

(defn change-secondary-contexts-inverted! [*state]
  (exec-cmd *state :change-secondary-contexts-inverted))

(defn show-events! [*state]
  (exec-cmd *state :enter-events-view))

(defn exit-events-view! [*state]
  (exec-cmd *state :exit-events-view))

(defn cycle-search-mode! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :cycle-search-mode)))

(defn delete-issue! [*state]
  (when (js/window.confirm "Delete currently selected issue?")
    (exec-cmd *state :delete-issue (:selected-issue @*state))))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (exec-cmd *state :delete-context (:selected-context @*state))))

(defn reprioritize-issue! [*state]
  (exec-cmd *state :reprioritize-issue))

(defn mark-issue-important! [*state]
  (exec-cmd *state :mark-issue-important))
