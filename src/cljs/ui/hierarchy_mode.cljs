(ns ui.hierarchy-mode
  "Hierarchy mode: the part-of layer on its own. With a context selected, the
   item list shows the nodes one level of the part-of edges below it, in path
   order, and nothing else -- items merely related to it are hidden, which is the
   point of the mode.

   It is session state, like danger mode: not persisted, not per-context. So it
   is not stored on the item the way a view is; it rides along in the state the
   SPA sends and the backend answers with the other list (see
   et.vp.ds.search/search-related-items).

   The level rides along the same way, and the same goes for it: it is not the
   context's, it is this session's reading of it. It is carried together with the
   id of the context it was counted under and counts for that one only -- level 2
   counted from one context names other things than level 2 counted from the next
   -- which is what puts it back to 1 when another context is selected, with
   nothing anywhere having to clear it.

   Turning it on has to re-ask for the item list, which is why toggling goes
   through fetch-and-reset! rather than a plain swap!. Stepping to another level
   is the same kind of move for the same reason."
  (:require [ui.actions.common :refer [fetch-and-reset!]]))

(defn toggle!
  [*state]
  (fetch-and-reset!
    *state
    ;; The level goes with the mode, both ways: cleared on the way out, so the
    ;; mode is entered at level 1 -- what it showed before there were levels at
    ;; all -- rather than at wherever it was left the time before.
    (let [state (dissoc @*state :hierarchy-level :hierarchy-max-level)]
      (if (:hierarchy-mode? @*state)
        (dissoc state :hierarchy-mode?)
        ;; The other half of the mutual exclusion: entering this mode leaves any
        ;; vector search, exactly as entering a vector search leaves this one.
        ;; See ui.actions/toggle-vector-search-mode!.
        (-> state
            (assoc :hierarchy-mode? true)
            (dissoc :vector-mode :vector-threshold
                    :vector-max-similarity :vector-min-similarity))))))

(defn- level
  "The level being read. It is kept as {:context <id> :level <n>} and counts for
   that context only -- level 2 of one context names other things than level 2 of
   the next -- so as soon as another one is selected this reads 1 again, with
   nothing having had to clear it. The backend applies the same rule to the same
   value when it builds the list (see et.vp.ds.search/level-asked-for), which is
   what keeps the number in the strip and the list beside it saying the same
   thing."
  [{:keys [hierarchy-level selected-item]}]
  (or (when (= (:context hierarchy-level) (:id selected-item)) (:level hierarchy-level)) 1))

(defn- deepest
  "The deepest level this context has anything at, as the backend counted it
   alongside the list (see repository/hierarchy-bound). Scoped to its context the
   same way the level is, so a bound counted for another one is no bound at all
   and the stepper offers nothing until the answer for this context arrives --
   erring, for the moment it takes, towards not offering a step rather than
   towards offering one that leads nowhere.

   0 -- nothing is a part of this context at all -- reads the same as 1 here:
   level 1 is what the mode shows either way, it is just empty."
  [{:keys [hierarchy-max-level selected-item]}]
  (or (when (= (:context hierarchy-max-level) (:id selected-item)) (:level hierarchy-max-level))
      0))

(defn- step!
  [*state to]
  (fetch-and-reset! *state
                    (assoc @*state
                      :hierarchy-level {:context (:id (:selected-item @*state)) :level to})))

(defn- arrow
  "One end of the stepper. A step that does not exist is not offered -- no
   handler, and dimmed -- rather than offered and then answered with an empty
   list. Clickable only: the owner has not settled on a key for this, and an
   unbound control can gain a binding later far more easily than a bound one can
   lose it."
  [*state id label to reachable? explain]
  [:span
   (cond-> {:id id
            :class (str "hierarchy-strip-step" (when-not reachable? " unavailable"))
            :title explain}
     reachable? (assoc :on-click (fn [_] (step! *state to))))
   label])

(defn strip
  "The status bar. Unlike the REC and DANGER badges it is not an overlay: it
   takes a row of its own at the top of the page and the app below it is shorter
   by its height (see layout.css). It says what mode we are in and, next to that,
   which level of the hierarchy the item list is showing."
  [*state]
  (when (:hierarchy-mode? @*state)
    (let [n (level @*state)
          bottom (deepest @*state)]
      [:div#hierarchy-strip [:span#hierarchy-strip-mode "Hierarchy mode"]
       ;; Nothing is selected, so the list is not a hierarchy and there is no
       ;; level to be at. The mode is still on, and the strip still says so.
       (when (:selected-item @*state)
         [:span#hierarchy-strip-level
          (arrow *state "hierarchy-level-down" "‹" (dec n) (> n 1)
                 (if (> n 1)
                   (str "Level " (dec n))
                   "Level 1 is the parts of this context; there is nothing above it"))
          [:span#hierarchy-level-value n]
          (arrow *state "hierarchy-level-up" "›" (inc n) (< n bottom)
                 (cond (< n bottom) (str "Level " (inc n))
                       (zero? bottom) "Nothing is a part of this context"
                       :else (str "Nothing under this context is filed deeper than level "
                                  bottom)))])])))
