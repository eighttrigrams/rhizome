(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.issues-list-item :as issues-list-item]
            [ui.actions :as actions]))

(defn- issues-list-component [*state]
  [:ul.cards
   (for [issue (:issues @*state)]
     ^{:key (:id issue)}
     [issues-list-item/component *state issue])])

(defn- related-issues-list-component [*state]
  [:ul.cards
   (for [[id title] (:related_issues (:selected-issue @*state))]
     ^{:key id}
     [:li.card.issue-card
      {:on-click #(actions/select-issue! *state {:id id})}
      [:div
       [issues-list-item/title-component title]]])])

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
