(ns ui.main.rhs.issues-list-item
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.rhs.context-badges :as context-badges]))

(defn info-component [state issue]
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
   {:class          (when (= (:id (:selected-issue @*state))
                             (:id issue)) :selected)
    :on-click       #(do (swap! *state (fn [state] (dissoc state :preview-issue)))
                         (actions/select-issue! *state issue))
    :on-mouse-enter #(when-not (:loading @*state) (swap! *state assoc :preview-issue issue))
    :on-mouse-leave #(swap! *state dissoc :preview-issue)}
   [:div
    [title-component (:title issue)]
    [info-component @*state issue]
    [context-badges/component (remove #(= (:id (:selected-context @*state))
                                          (first %)) 
                                      (:contexts issue))]]])