(ns ui.main.lhs.list-item
  (:require [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]
            [ui.main.rhs.issues-list-items :as ili]))

(defn component [*state context]
  [:li.card.issue-card
   {:class    (when (= (:id (:selected-context @*state)) ;; TODO review on :id
                       (:id context)) :selected)
    :on-click #(actions/select-context! *state context false)}
   [:div.issue-card-inner-as-of-yet-unused
    [ili/title-component (:title context) (:data context)]
    [context-badges/component (:contexts (:data context))]]])
