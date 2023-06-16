(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [clojure.string :as str]))

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

(defn- the-issue-itself-component [{:keys [title description date]}]
  [:<>
   (when date [:b date])
   [:span
    {:style {:font-size "35px"}}
    [:> ReactMarkdown
     {:children title}]]
   (when (and description (str/includes? description "https://www.youtube.com/watch")) 
     (let [found (re-find #"https://www.youtube.com/watch.*?\s" description)
           found (if-not found (re-find #"https://www.youtube.com/watch.*?$" description) found)
           found (str/replace (str/trim found) "watch?v=" "embed/")]
       [:iframe {:width "420px" 
                 :height "315px"
                 :src found
                 :allowFullScreen true}]))
   [:div.description
    [:> ReactMarkdown
     {:children description}]]])

(defn component [*state]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [contexts]} selected-issue]
    [:<>
     [:h4 (if selected-context 
            
            [:div
             {:on-click #(actions/deselect-issue! *state)}
             (str "[" (:title selected-context) "]")] 
            
            "[Overview]")]
     [context-links-component *state contexts]
     [:hr]
     [the-issue-itself-component selected-issue]]))

(defn preview-component [issue]
  (the-issue-itself-component issue))
