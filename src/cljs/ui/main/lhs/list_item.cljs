(ns ui.main.lhs.list-item
  (:require [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]))

(defn component [*state context]
  [:li.card.issue-card
   {:class    (when (= (:id (:selected-context @*state)) ;; TODO review on :id
                       (:id context)) :selected)
    :on-click #(actions/select-context! *state context)}
   [:div
    [:span.title.title1 (:title context)]
    [context-badges/component (dissoc (:contexts context) nil)]]])
