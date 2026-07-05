(ns ui.actions
  (:require [ui.actions.common :refer
             [fetch-and-reset! fetch-and-reset-with-method! fetch-and-reset-with-method-2!]]
            api
            [goog.async.Debouncer]
            utils
            [ui.main.rhs.modifiers :as modifiers]))

(defn fetch! [*state] (fetch-and-reset! *state @*state))

(defn quit-search!
  [*state]
  (cond (= :contexts (:active-search @*state)) (fetch-and-reset!
                                                 *state
                                                 (-> @*state
                                                     (assoc :active-search :items)
                                                     (dissoc :preview-item :link-item :link-context :q)))
        (= :items (:active-search @*state))
          (fetch-and-reset! *state
                            (-> @*state
                                (dissoc :preview-item :active-search :link-item :link-context :q)))))

(defn load-stored-context
  [*state idx]
  (fetch-and-reset-with-method! *state @*state api/load-stored-context idx))

(defn remove-stored-context
  [*state idx]
  (fetch-and-reset-with-method! *state @*state api/remove-stored-context idx))

(defn store-current-view!
  [*state item]
  (fetch-and-reset-with-method! *state (dissoc @*state :modal) api/store-current-view item))

(defn new-item!
  [*state item]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :items)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-item item))

(defn new-context!
  [*state context]
  ;; TODO do next line in backend
  (swap! *state dissoc :aggregated-contexts)
  (swap! *state dissoc :items)
  (swap! *state dissoc :modal)
  (fetch-and-reset-with-method-2! *state api/insert-context context))

(defn- select-context-or-item!
  [*state context select-as-item?]
  (reset! *state (assoc @*state
                   :active-search (when-not select-as-item? :items)
                   :item-view? select-as-item?
                   :old-selected-item (:selected-item @*state)
                   ;; For a snappy response in the UI, set :selected-item immediately. The
                   ;; subsequent call to fetch-and-reset! then will fetch and replace it,
                   ;; thereby filling in the related items.
                   :selected-item context))
  (fetch-and-reset-with-method-2! *state api/fetch-context [context select-as-item?]))

(defn select-last-context! [*state] (fetch-and-reset-with-method-2! *state api/select-last-context))

(defn deselect-context! [*state] (fetch-and-reset-with-method-2! *state api/deselect-context))

(defn delete-item!
  [*state idx]
  (when (js/window.confirm "Delete this item?")
    (fetch-and-reset-with-method-2! *state api/delete-item idx)))

(defn unlink-item!
  [*state idx]
  (when (js/window.confirm "Unlink this item?")
    (fetch-and-reset-with-method-2! *state api/unlink-item idx)))

(defn delete-context!
  [*state]
  (when (js/window.confirm "Delete currently selected context?")
    (fetch-and-reset-with-method-2! *state api/delete-context (:selected-item @*state))))

(defn select-context!
  ([*state context] (select-context! *state context false false))
  ([*state context shift-pressed? alt-pressed?]
   (if (true? (:link-context @*state))
     (fetch-and-reset-with-method! *state
                                   @*state
                                   api/link-selected-context-to-context
                                   context
                                   shift-pressed?
                                   alt-pressed?)
     (select-context-or-item! *state context false))))

(defn deselect-secondary-contexts!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/deselect-secondary-contexts))

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

(defn select-first-context!
  [*state shift-pressed? alt-pressed?]
  (when (seq (:contexts @*state))
    (select-context! *state (first (:contexts @*state)) shift-pressed? alt-pressed?)))

(defn- select-nth-context!
  [*state n shift-pressed? alt-pressed?]
  (when (and (seq (:contexts @*state)) (> (count (:contexts @*state)) n))
    (select-context! *state (nth (:contexts @*state) n) shift-pressed? alt-pressed?)))

(defn select-second-context!
  [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 1 shift-pressed? alt-pressed?))

(defn select-third-context!
  [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 2 shift-pressed? alt-pressed?))

(defn select-fourth-context!
  [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 3 shift-pressed? alt-pressed?))

(defn select-fifth-context!
  [*state shift-pressed? alt-pressed?]
  (select-nth-context! *state 4 shift-pressed? alt-pressed?))

(defn reprioritize-item
  [*state item]
  (fetch-and-reset-with-method! *state @*state api/reprioritize-item item))

(defn select-first-item!
  [*state shift-pressed? alt-pressed?]
  (when (seq (:items @*state))
    (select-item! *state (first (:items @*state)) shift-pressed? alt-pressed?)))

(defn- select-nth-item!
  [*state n shift-pressed? alt-pressed?]
  (when (and (seq (:items @*state)) (> (count (:items @*state)) n))
    (select-item! *state (nth (:items @*state) n) shift-pressed? alt-pressed?)))

(defn select-second-item!
  [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 1 shift-pressed? alt-pressed?))

(defn select-third-item!
  [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 2 shift-pressed? alt-pressed?))

(defn select-fourth-item!
  [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 3 shift-pressed? alt-pressed?))

(defn select-fifth-item!
  [*state shift-pressed? alt-pressed?]
  (select-nth-item! *state 4 shift-pressed? alt-pressed?))

(defn start-context-search
  [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                      :cmd :start-context-search
                      :active-search :contexts
                      ;; TODO maybe move those into repository; also add this to for
                      ;; example 'i' and other modes
                      :link-item false
                      :link-context false)))

(defn start-linking-context
  [*state]
  (fetch-and-reset! *state
                    (assoc @*state
                      :cmd :start-linking-selected-item-to-context
                      :active-search :contexts
                      :link-context true)))

(defn unlink-selected-item-from-selected-context
  [*state]
  (fetch-and-reset-with-method! *state @*state api/unlink-selected-item-from-container))

(defn upgrade-item-to-context!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/upgrade-item-to-context))

(defn link-item-to-selected-item!
  [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-item-to-selected-item)))

(defn search! [*state] (fetch-and-reset! *state @*state))

(defn vector-search! [*state]
  (fetch-and-reset-with-method! *state @*state api/vector-search-related-items))

(defn vector-threshold-search!
  "Blue-mode fetch triggered by entering the mode or changing the query.
   Snaps the threshold to the query's max similarity by clearing
   :vector-threshold; the backend returns the effective threshold and the
   similarity bounds, which merge back into state (positioning the slider)."
  [*state]
  (swap! *state assoc :vector-threshold nil)
  (fetch-and-reset-with-method! *state @*state api/vector-threshold-search-related-items))

(defn- vector-threshold-fetch! [*state]
  (fetch-and-reset-with-method! *state @*state api/vector-threshold-search-related-items))

(def ^:private vector-threshold-fetch-debounced! (utils/debounce vector-threshold-fetch! 120))

(defn set-vector-threshold!
  "Slider handler: set the threshold immediately (so the thumb tracks) and
   issue a debounced backend query with it. Filtering happens server-side."
  [*state v]
  (swap! *state assoc :vector-threshold v)
  (vector-threshold-fetch-debounced! *state))

(defn toggle-vector-search-mode!
  "Cycle the vector search mode: off -> green (re-rank by similarity) ->
   blue (original order + similarity threshold filter) -> off."
  [*state]
  (let [next-mode (case (:vector-mode @*state)
                    :green :blue
                    :blue  nil
                    :green)]
    (swap! *state assoc :vector-mode next-mode)
    (case next-mode
      :green (vector-search! *state)
      :blue  (vector-threshold-search! *state)
      (do (swap! *state dissoc :vector-threshold :vector-max-similarity :vector-min-similarity)
          (search! *state)))))

(defn change-secondary-contexts-selection!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/change-secondary-contexts-selection))

(defn change-secondary-contexts-unassigned-selected!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/change-secondary-contexts-unassigned-selected))

(defn change-secondary-contexts-inverted
  [*state]
  (fetch-and-reset-with-method! *state @*state api/change-secondary-contexts-inverted))

(defn change-description-filter!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/change-description-filter))

(defn cycle-search-mode!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/cycle-search-mode))

(defn enter-item-view! [*state] (swap! *state assoc :item-view? true))

(defn exit-item-view! [*state] (swap! *state assoc :item-view? false))

(defn fetch-item-description!
  [*state item]
  ;; Reset description version index when fetching a new item
  (swap! *state assoc :description-version-idx 0)
  ;; This runs on hover (on-mouse-enter). Merge ONLY the fetched description into the
  ;; current preview-item; never reset! the whole app state from a now-snapshot. The
  ;; fetch can resolve *after* the user has already clicked/selected another item, and
  ;; a full reset from the stale hover snapshot would clobber that selection's :items /
  ;; :selected-item (the observed racy empty/stale related-items list). Guarding on the
  ;; preview-item id also drops a resolution for an item the pointer has since left.
  (-> (api/fetch-item-description @*state item)
      (.then (fn [result]
               (when (and (not (:ignore-item-description result))
                          (= (:id (:preview-item @*state)) (:id item)))
                 (swap! *state assoc-in [:preview-item :description]
                        (:item-description result)))))))

(defn open-annotation-edit-modal!
  [*state item]
  (js/console.log "Setting modal to :annotation-edit")
  (js/console.log "State before:" (clj->js @*state))
  (swap! *state assoc :modal :annotation-edit :annotation-edit-item item)
  (js/console.log "State after:" (clj->js @*state)))

(defn edit-item-in-obsidian!
  [*state]
  (let [selected-item (:selected-item @*state)]
    (when selected-item
      (fetch-and-reset-with-method! *state @*state api/edit-item-in-obsidian selected-item))))
