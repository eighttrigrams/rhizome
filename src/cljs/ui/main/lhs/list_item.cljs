(ns ui.main.lhs.list-item
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]))

;; TODO dedup with title-component form issues-list-items
(defn title-component [title]
  [:span.title
   [:> ReactMarkdown
    {:children title}]])

(defn component [*state context]
  [:li.card.issue-card
   {:class    (when (= (:id (:selected-context @*state)) ;; TODO review on :id
                       (:id context)) :selected)
    :on-click #(actions/select-context! *state context)}
   [:div
    [title-component (:title context)]
    [context-badges/component (:contexts context)]]])
