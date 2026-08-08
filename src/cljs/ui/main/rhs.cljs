(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.refusal :as refusal]
            [ui.main.rhs.items-list-items :as items-list-items]))

(defn- scroll-into-view
  [id]
  (js/setTimeout
    #(do (.scrollIntoView (.getElementById js/document (str "item-card-" id))
                          (clj->js {:behavior "instant" :block "center" :inline "nearest"}))
         (.scrollIntoView (.getElementById js/document (str "item-card-" id))
                          (clj->js {:behavior "smooth" :block "start" :inline "nearest"})))
    1000))

(defn- items-list-component
  [*state]
  [:ul.cards
   ;; Keyed by position and not by id alone: hierarchy mode lists a node once per
   ;; path, so the same id can legitimately appear twice in one list -- an item
   ;; filed under two chapters of the same book sits at two places of the level
   ;; below it. React treats a repeated key as one child and is free to drop or
   ;; reorder the other, which is exactly the second position that was the point
   ;; of listing it twice.
   (map-indexed (fn [idx item] [:<> {:key (str idx "-" (:id item))}
                                [items-list-items/regular-items-list-item-component *state item idx
                                 {:allow-delete-on-right-click? true
                                  :show-relation-annotation? true
                                  :select-fn #(scroll-into-view %)
                                  :show-context-selector? true
                                  :rhs? true}]])
                (:items @*state))])

(defn component
  [_*state]
  (fn [*state]
    (let [state @*state]
      [:<>
       ;; Above the list the refused row is still sitting in, because that list
       ;; is the answer the user is looking at: both ways to unlink -- the
       ;; right-click on a card and Alt+T on the selection -- leave it on screen
       ;; unchanged, and the banner is what says the row stayed on purpose.
       (when-let [msg (:unlink-refused state)]
         [refusal/component msg
          "Nothing was unlinked. Link it to another context first, or delete it instead."])
       (when (= :items (:active-search state)) [input/component *state])
       [:div.scrollable {:class (when (= :items (:active-search state)) :search-active)}
        (when (not= :description (:modal state)) [items-list-component *state])]])))
