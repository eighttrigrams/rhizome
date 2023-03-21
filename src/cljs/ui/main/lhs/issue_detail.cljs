(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]))

(defn- context-links-component [*state related-contexts]
  (when (seq related-contexts)
    [:<>
     [:h3 "Contexts"]
     [:ul
      (map (fn [[id title]]
             [:li
              {:key      id
               :on-click #(actions/select-context! *state {:id id} true)}
              title])
           related-contexts)]]))

(defn component [*state]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [title description contexts]} selected-issue]
    [:<>
     [:h4 (if selected-context (str "[" (:title selected-context) "]") "[Overview]")]
     [context-links-component *state contexts]
     [:hr]
     [:span
      {:style {:font-size "35px"}}
      [:> ReactMarkdown
       {:children title}]]
     [:> ReactMarkdown
      {:children description}]]))
