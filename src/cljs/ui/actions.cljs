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
  (reset! *state (assoc (dissoc @*state
                          :unlink-refused
                          ;; Provenance is about one item's text. Both keys go
                          ;; here rather than only the page flag: leaving the
                          ;; data behind would show the previous item's lines
                          ;; for as long as it takes the next fetch to land,
                          ;; under the new item's id, and that reads as an
                          ;; answer rather than as a gap.
                          :provenance-page?
                          :provenance)
                   ;; The refusal names one row and one whole. Moving the
                   ;; selection changes the list it was standing over, so it
                   ;; stops being about anything the user can still see.
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

(defn filed-under
  "The whole a row is shown under in the list it came from.

   For every list but hierarchy mode's that is the selected context, and it was
   true of hierarchy mode too while every row was a direct part of it. Below
   level 1 it stops being true: a row is shown under the selected context and is
   filed under something further down. The relation fields the row carries -- its
   annotation, its sibling index -- belong to that further-down edge, so anything
   acting on what the card displays has to aim there and not at the selection.

   The id comes off the row (`part-of-level` projects it); the title off the
   row's own contexts map, which is where the badges under it come from, so the
   name this returns is a name already on screen."
  [state item]
  (let [whole-id (:part_of_whole_id item)]
    (cond (nil? whole-id) (:selected-item state)
          (= whole-id (:id (:selected-item state))) (:selected-item state)
          :else {:id whole-id
                 :title (or (get-in item [:data :contexts whole-id :title]) (str whole-id))})))

(defn unlink-item!
  [*state item]
  (let [whole (filed-under @*state item)]
    ;; Naming it. The gesture unfiles the row from where it is shown, and below
    ;; level 1 where it is shown is not where the eye starts -- "Unlink this
    ;; item?" on its own left nothing in the confirmation that could say which
    ;; of two edges was about to go.
    (when (js/window.confirm (str "Unlink \"" (:title item) "\" from \"" (:title whole) "\"?"))
      ;; Clear last time's refusal before asking again, the way the edit modal
      ;; clears :part-of-refused on its way into a save (ui.modals.actions):
      ;; the response only carries the key when it is refused again, so an
      ;; unlink that goes through has to be what takes the banner down.
      (swap! *state dissoc :unlink-refused)
      (fetch-and-reset-with-method-2! *state api/unlink-item item whole))))

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
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :unlink-refused)
                                api/unlink-selected-item-from-container))

(defn upgrade-item-to-context!
  [*state]
  (fetch-and-reset-with-method! *state @*state api/upgrade-item-to-context))

(defn link-item-to-selected-item!
  [*state]
  (fetch-and-reset! *state (assoc @*state :cmd :link-item-to-selected-item)))

(defn search! [*state] (fetch-and-reset! *state @*state))

(defn- vector-fetch!
  "Every vector request leaves through here, so the rule that none of them
   happens in hierarchy mode is stated once.

   Neither mode is taught about the other, by design -- which means the backend
   will answer a vector request that happens to carry :hierarchy-mode? with an
   ordinary related-items list, and that list then installs itself under the
   hierarchy strip with the intersection section hidden. Entering either mode
   leaves the other, so the only way to be here at all is a request that
   outlived the state it was scheduled in: the threshold fetch is debounced by
   120ms and reads state when it *fires*, so a toggle inside that window would
   otherwise send one. Dropping it is the right answer -- it would be answering
   a question about a mode the user has already left."
  [*state method]
  (when-not (:hierarchy-mode? @*state)
    (fetch-and-reset-with-method! *state @*state method)))

(defn vector-search! [*state]
  (vector-fetch! *state api/vector-search-related-items))

(defn vector-threshold-search!
  "Blue-mode fetch triggered by entering the mode or changing the query.
   Snaps the threshold to the query's max similarity by clearing
   :vector-threshold; the backend returns the effective threshold and the
   similarity bounds, which merge back into state (positioning the slider)."
  [*state]
  (swap! *state assoc :vector-threshold nil)
  (vector-fetch! *state api/vector-threshold-search-related-items))

(defn- vector-threshold-fetch! [*state]
  (vector-fetch! *state api/vector-threshold-search-related-items))

(def ^:private vector-threshold-fetch-debounced! (utils/debounce vector-threshold-fetch! 120))

(defn set-vector-threshold!
  "Slider handler: set the threshold immediately (so the thumb tracks) and
   issue a debounced backend query with it. Filtering happens server-side."
  [*state v]
  (swap! *state assoc :vector-threshold v)
  (vector-threshold-fetch-debounced! *state))

(defn toggle-vector-search-mode!
  "Cycle the vector search mode: off -> green (re-rank by similarity) ->
   blue (original order + similarity threshold filter) -> off.

   Entering a vector mode leaves hierarchy mode. The two are mutually exclusive
   by design: one lists a structure in the order the human put it in, the other
   ranks or filters by similarity to a query, and no list can be both. Neither
   mode is taught about the other -- they simply do not coexist, so entering
   either one leaves the other."
  [*state]
  (let [next-mode (case (:vector-mode @*state)
                    :green :blue
                    :blue  nil
                    :green)]
    (swap! *state (fn [s]
                    (cond-> (assoc s :vector-mode next-mode)
                      next-mode (dissoc :hierarchy-mode?))))
    (case next-mode
      :green (vector-search! *state)
      :blue (vector-threshold-search! *state)
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

(defn open-provenance!
  "Open the Provenance page on the selected item's current description.

   The page flag goes up in the same state the request is made from, so it is
   already on when the answer merges in -- the page opens at once and fills in,
   rather than waiting on a round trip before anything happens, which is how
   the config and diff pages behave too.

   Whatever version the bar happens to be showing: the server reads the current
   description (repository/fetch-item-provenance) and the page has no say in
   it. Provenance is about the item, not about the version on screen."
  [*state]
  (when-let [item (:selected-item @*state)]
    (fetch-and-reset-with-method! *state
                                  (assoc @*state :provenance-page? true)
                                  api/fetch-item-provenance
                                  item)))

(defn open-annotation-edit-modal!
  [*state item]
  ;; The whole is settled here, from the row that was clicked, rather than read
  ;; off the selection when the modal renders or when it saves: those are two
  ;; more places to make the same wrong guess, and the row is only in hand here.
  ;; Opened clean: a refusal the last save left standing belongs to that save,
  ;; and a modal that comes up already telling the user something was declined
  ;; is telling them about a write they are no longer making.
  (swap! *state #(-> %
                     (dissoc :part-of-refused :save-failed)
                     (assoc :modal :annotation-edit
                            :annotation-edit-item item
                            :annotation-edit-context (filed-under % item)))))

(defn edit-item-in-obsidian!
  [*state]
  (let [selected-item (:selected-item @*state)]
    (when selected-item
      (fetch-and-reset-with-method! *state @*state api/edit-item-in-obsidian selected-item))))
