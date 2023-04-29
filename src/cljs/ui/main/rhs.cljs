(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.issues-list-item :as issues-list-item]
            [ui.actions :as actions]
            [ui.main.rhs.context-badges :as context-badges]))

(defn- issues-list-component [*state]
  [:ul.cards
   (for [issue (:issues @*state)]
     ^{:key (:id issue)}
     [issues-list-item/component *state issue])])

(defn- related-issues-list-item-component [*state {:keys [id title contexts] :as issue}]
  [:li.card.issue-card
   {:on-click #(do (swap! *state (fn [state] ;; TODO review and dedup with issues-list-item/component
                                   (-> state
                                       (dissoc :preview-issue))))
                   (actions/select-issue! *state {:id id}))
    :on-mouse-enter #(when-not (:loading @*state) (swap! *state assoc :preview-issue issue))
    :on-mouse-leave #(swap! *state dissoc :preview-issue)}
   [:div
    [issues-list-item/title-component title]
    [issues-list-item/info-component @*state issue]
    [context-badges/component contexts]]])

(defn- related-issues-list-component [*state]
  [:ul.cards
   (for [related-issue (:related_issues (:selected-issue @*state))]
     ^{:key (:id related-issue)}
     [related-issues-list-item-component *state related-issue])])

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
