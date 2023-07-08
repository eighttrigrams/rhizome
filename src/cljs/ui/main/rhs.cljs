(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.issues-list-items :as issues-list-items]))

(defn- issues-list-component [*state]
  [:ul.cards
   (for [issue (:issues @*state)]
     ^{:key (:id issue)}
     [issues-list-items/regular-issues-list-item-component *state issue])])

(defn- related-issues-list-component [*state]
  [:ul.cards
   (for [related-issue (:related_issues (:selected-issue @*state))]
     ^{:key (:id related-issue)}
     [issues-list-items/related-issues-list-item-component *state related-issue])])

(defn component [_*state]
  (fn [*state]
    (let [state @*state]
      [:<>
       (when (= :issues (:active-search state))
         [input/component *state])
       [:div.scrollable
        {:class (when (= :issues (:active-search state)) :search-active)}
        (if (or (not (:selected-issue state))
                (= :issues (:active-search state)))
          [issues-list-component *state]
          [related-issues-list-component *state])]])))
