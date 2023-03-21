(ns ui.main.rhs.issues-list-item
  (:require [ui.actions :as actions]
            ["react-markdown$default" :as ReactMarkdown]))

(defn- context-badges-component [state contexts]
  [:span.contexts
   (doall
    (->> contexts
         (filter (fn [[idx _title]]
                   (not= idx (:id (:selected-context state)))))
         (map (fn [[idx title]]
                [:span.badge {:key idx}
                 title]))))])

(defn- info-component [state issue]
  [:span.info
   (when (and (:selected-context state)
              (not= 0 (:search_mode (:selected-context state)))
              (not (and (= (:short_title_ints issue) 0)
                        (empty? (:short_title issue)))))
     (str "["
          (if (> (:short_title_ints issue) 0)
            (:short_title_ints issue)
            (:short_title issue))
          "] "))
   [:span.date (:date issue)]])

;; TODO extract ns
(defn title-component [title]
  [:span.title
   [:> ReactMarkdown
    {:children title}]])

(defn component [*state issue]
  [:li.card.issue-card
   {:class     (when (= (:id (:selected-issue @*state))
                        (:id issue)) :selected)
    :on-click #(actions/select-issue! *state issue)}
   [:div
    {:class (when (:important issue) :important)}
    [title-component (:title issue)]
    [info-component @*state issue]
    [context-badges-component @*state (:contexts issue)]]])
