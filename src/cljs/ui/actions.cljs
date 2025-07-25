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
                                 (assoc :active-search :items)
                                 (dissoc :preview-item 
                                         :link-item 
                                         :q))) 
    (= :items (:active-search @*state))
    (fetch-and-reset! *state (-> @*state 
                                 (dissoc :preview-item 
                                         :active-search
                                         :link-item 
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

(defn new-item! [*state item]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :items)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-item item))

(defn new-context! [*state context]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :items)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-context context))

(defn- select-context-or-item! [*state context select-as-item?]
  (reset! *state (assoc @*state
                       :active-search (when-not select-as-item? :items)
                       :item-view? select-as-item?
                       :old-selected-context (:selected-context @*state)
                       ;; For a snappy response in the UI, set :selected-item immediately.
                       ;; The subsequent call to fetch-and-reset! then
                       ;; will fetch and replace it, thereby filling in the related items.
                       :selected-context context))
  (fetch-and-reset-with-method-2! 
   *state 
   api/fetch-context
   [context select-as-item?]))

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
     (select-context-or-item! *state context false))))

(defn deselect-secondary-contexts! [*state]
  (fetch-and-reset-with-method! *state
                                @*state
                                api/deselect-secondary-contexts))

(defn select-item!
  ([*state item] (select-item! *state item false false))
  ([*state item shift-pressed? alt-pressed?]
   (if (:link-item @*state)
     (fetch-and-reset-with-method! *state
                                   @*state
                                   api/finish-linking-item 
                                   (:id item)
                                   shift-pressed? 
                                   alt-pressed?)
     (select-context-or-item! *state item true))))

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

(defn reprioritize-item [*state item]
  (fetch-and-reset-with-method! *state
                                @*state 
                                api/reprioritize-item 
                                item))

(defn select-first-item! [*state shift-pressed? alt-pressed?]
  (when (seq (:items @*state))
    (select-item! *state (first (:items @*state)) shift-pressed? alt-pressed?)))

(defn- select-nth-item! [*state n shift-pressed? alt-pressed?]
  (when (and (seq (:items @*state))
             (> (count (:items @*state)) n))
    (select-item! *state (nth (:items @*state) n) shift-pressed? alt-pressed?)))

(defn select-second-item! [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 1 shift-pressed? alt-pressed?))

(defn select-third-item! [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 2 shift-pressed? alt-pressed?))

(defn select-fourth-item! [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 3 shift-pressed? alt-pressed?))

(defn select-fifth-item! [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 4 shift-pressed? alt-pressed?))

(defn start-context-search [*state]
  (fetch-and-reset! *state 
                    (assoc @*state
                           :cmd :start-context-search
                           :active-search :contexts
                           ;; TODO maybe move those into repository; also add this to for example 'i' and other modes
                           :link-item false
                           :link-context false)))

(defn start-linking-context [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                           :cmd :start-linking-selected-item-to-context
                           :active-search :contexts
                           :link-context true)))

(defn unlink-selected-item-from-selected-context [*state]
  (fetch-and-reset-with-method! *state @*state api/unlink-selected-item-from-container))

(defn upgrade-item-to-context! [*state]
  (fetch-and-reset-with-method! *state @*state api/upgrade-item-to-context))

(defn link-item-to-selected-context! [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-item-to-selected-context)))

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

(defn enter-item-view! [*state]
  (swap! *state assoc :item-view? true))

(defn exit-item-view! [*state]
  (swap! *state assoc :item-view? false))

(defn fetch-item-description! [*state item]
  (fetch-and-reset-with-method! *state @*state api/fetch-item-description item :dont-reset-preview-item))
