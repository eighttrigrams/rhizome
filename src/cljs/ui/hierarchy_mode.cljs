(ns ui.hierarchy-mode
  "Hierarchy mode: the part-of layer on its own. With a context selected, the
   item list shows that context's parts, in sibling order, and nothing else --
   items merely related to it are hidden, which is the point of the mode.

   It is session state, like danger mode: not persisted, not per-context. So it
   is not stored on the item the way a view is; it rides along in the state the
   SPA sends and the backend answers with the other list (see
   et.vp.ds.search/search-related-items).

   Turning it on has to re-ask for the item list, which is why toggling goes
   through fetch-and-reset! rather than a plain swap!."
  (:require [ui.actions.common :refer [fetch-and-reset!]]))

(defn toggle!
  [*state]
  (fetch-and-reset! *state
                    (if (:hierarchy-mode? @*state)
                      (dissoc @*state :hierarchy-mode?)
                      (assoc @*state :hierarchy-mode? true))))

(defn strip
  "The status bar. Unlike the REC and DANGER badges it is not an overlay: it
   takes a row of its own at the top of the page and the app below it is shorter
   by its height (see layout.css). It says only what mode we are in for now --
   the space is reserved because navigating the hierarchy will live here."
  [*state]
  (when (:hierarchy-mode? @*state)
    [:div#hierarchy-strip [:span#hierarchy-strip-mode "Hierarchy mode"]]))
