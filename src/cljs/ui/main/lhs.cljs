(ns ui.main.lhs
  (:require [ui.main.input :as input]
            [ui.main.lhs.context-detail :as context-detail]
            [ui.main.lhs.issue-detail :as issue-detail]
            [ui.main.rhs.issues-list-items :as issues-list-items]))

(defn- contexts-list [*state]
  [:ul.cards
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [issues-list-items/regular-issues-list-item-component *state context nil nil {}]))])

(defn component [_*state]
  (fn [*state]
    (cond
      (and (:preview-issue @*state)
           (not (:loading @*state))
           (not (= :contexts (:active-search @*state))))
      [:div.details-component.scrollable
       [issue-detail/preview-component (:preview-issue @*state)]]
      (= :contexts (:active-search @*state))
      [:<>
       [input/component *state]
       [:div.scrollable
        {:class :search-active}
        [contexts-list *state]]]
      (:issue-view? @*state)
      [:div.details-component.scrollable
       [issue-detail/component *state]]
      (:selected-context @*state)
      [:<>
       [:ul.cards [issues-list-items/regular-issues-list-item-component *state (:selected-context @*state) nil nil {}]]
       [:div.scrollable.card-shown.details-component
        [context-detail/component *state]]]
      :else
      [:div.scrollable
       [contexts-list *state]])))
