(ns ui.main.lhs.list-item
  (:require [ui.actions :as actions]))

(defn component [*state context]
  [:li.card
   {:class    (when (= (:id (:selected-context @*state)) ;; TODO review on :id
                       (:id context)) :selected)
    :on-click #(actions/select-context! *state context)}
   [:div
    (:title context)]])
