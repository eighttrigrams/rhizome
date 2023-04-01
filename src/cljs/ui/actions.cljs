(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset!]]
            api))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn quit-search! [*state]
  (fetch-and-reset! *state (dissoc @*state :active-search :search-globally?)))

(defn deselect-context! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :deselect-context))))

(defn select-context! 
  ([*state context] (select-context! *state context false))
  ([*state context suppress-reset-issue]
   ;; See below
   (swap! *state assoc :selected-context context)
   (fetch-and-reset! *state (-> @*state
                                ;; TODO review; simplify
                                (assoc :selected-secondary-contexts-ids #{})
                                (assoc :secondary-contexts-inverted? false)
                                (assoc :unassigned-secondary-contexts-selected? false)
                                (assoc :context-to-fetch context)
                                (#(if-not suppress-reset-issue
                                    (dissoc % :selected-issue) ;; TODO review
                                    (identity %)))))))

(defn select-issue! [*state issue]
  ;; For a snappy response in the UI, set :selected-issue immediately.
  ;; The subsequent call to fetch-and-reset! then
  ;; will fetch and replace it, thereby filling in the related issues.
  (swap! *state assoc :selected-issue issue)
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :fetch-issue)
                               (assoc :arg issue))))

(defn search! [*state value]
  (fetch-and-reset! *state @*state value))

(defn deselect-secondary-contexts! [*state]
  ;; TODO dedup, extract common pattern here
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :deselect-secondary-contexts))))

(defn change-secondary-contexts-selection! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :change-secondary-contexts-selection))))

(defn change-secondary-contexts-unassigned-selected! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :change-secondary-contexts-unassigned-selected))))

(defn change-secondary-contexts-inverted! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :change-secondary-contexts-inverted))))

(defn show-events! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :enter-events-view)
                               (assoc :show-events? true) ;; TODO set this in the backend
                               )))

(defn exit-events-view! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :cmd :exist-events-view)
                               (assoc :show-events? false))))

(defn cycle-search-mode! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :cycle-search-mode)))

(defn delete-issue! [*state]
  (when (js/window.confirm "Delete currently selected issue?")
    (fetch-and-reset! *state (-> @*state
                                 (assoc :cmd :delete-issue)
                                 (assoc :arg (:selected-issue @*state))))))

(defn delete-context! [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (fetch-and-reset! *state (-> @*state 
                                 (assoc :cmd :delete-context)
                                 (assoc :arg (:selected-context @*state))))))

(defn reprioritize-issue! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :reprioritize-issue)))

(defn mark-issue-important! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :mark-issue-important)))
