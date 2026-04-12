(ns ui.modals.item-edit
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.modals.link-context-item :as link-context-item]
            [clojure.string :as str]
            [utils :as utils]
            api))

(defn- get-title-el [] (.getElementById js/document "item-title"))

(defn- get-short-title-el [] (.getElementById js/document "item-short-title"))

(defn- get-annotation-el [] (.getElementById js/document "item-annotation"))

(defn- get-sort-idx-el [] (.getElementById js/document "item-sort-idx"))

(defn- get-tags-el [] (.getElementById js/document "item-tags"))

(defn- get-highlighted-secondary-contexts-el
  []
  (.getElementById js/document "item-highlighted-secondary-contexts"))

(defn- get-event-el [] (.getElementById js/document "has-date"))

(defn- get-date-el [] (.getElementById js/document "date-picker"))

(def *related-items (r/atom {}))
(def *resource-links (r/atom []))

(defn resource-links-component
  [item]
  [:<> [:h4 "Resource Links"]
   (for [{:keys [id k v]} @*resource-links]
     ^{:key id}
     [:div {:style {:display "flex" :gap "10px" :margin-bottom "5px" :align-items "center"}}
      [:input
       {:type "text"
        :value k
        :placeholder "Key"
        :style {:width "150px"}
        :on-change (fn [e]
                     (let [new-key (.-value (.-target e))]
                       (swap! *resource-links
                         (fn [links]
                           (mapv (fn [entry] (if (= (:id entry) id) (assoc entry :k new-key) entry))
                             links)))))}]
      [:input
       {:type "text"
        :value v
        :placeholder "Value"
        :style {:flex "1"}
        :on-change (fn [e]
                     (let [new-val (.-value (.-target e))]
                       (swap! *resource-links
                         (fn [links]
                           (mapv (fn [entry] (if (= (:id entry) id) (assoc entry :v new-val) entry))
                             links)))))}]
      [:button
       {:on-click (fn [_] (swap! *resource-links (fn [links] (filterv #(not= (:id %) id) links))))}
       "Remove"]])
   [:button {:on-click (fn [_] (swap! *resource-links conj {:id (random-uuid) :k "" :v ""}))}
    "+ Add Resource Link"]])

(defn basic-elements-component
  [item]
  (r/create-class
    {:component-did-mount #(do (editor/create (get-title-el) {:input-field-mode? true})
                               (editor/create (get-short-title-el) {:input-field-mode? true})
                               (editor/create (get-tags-el) {:input-field-mode? true}))
     :reagent-render ;
       (fn [_item]
         [:<> [:div {:style {:margin-bottom "10px"}} "id: " (:id item)]
          [:div
           [:input#item-title.line
            {:autoComplete :off :defaultValue (:title item) :placeholder "Title"}]]
          [:div
           [:input#item-short-title.line
            {:autoComplete :off :defaultValue (:short_title item) :placeholder "Short title"}]]
          [:div
           [:input#item-annotation.line
            {:autoComplete :off :defaultValue (:annotation item) :placeholder "Annotation"}]]
          [:div
           [:input#item-sort-idx.line
            {:autoComplete :off
             :defaultValue (utils/sort-idx->display (:sort_idx item))
             :placeholder "Sort index (number or roman numeral)"}]]
          [:div
           [:input#item-tags.line
            {:autoComplete :off :defaultValue (:tags item) :placeholder "Tags"}]]
          [:div
           [:input#item-highlighted-secondary-contexts.line
            {:autoComplete :off
             :defaultValue (str/join " " (:highlighted-secondary-contexts (:data item)))
             :placeholder "Highlighted secondary contexts"}]]])}))

(defn event-component
  [item *date-visible?]
  [:div {:style {:display "flex" :align-items "center" :gap "10px"}} [:h4 "Event"]
   [:span "Has event?"]
   [:input#has-date
    {:type :checkbox :defaultChecked @*date-visible? :on-click #(swap! *date-visible? not)}]
   (when @*date-visible?
     [:<> [:span "Event"] [:input#date-picker {:type :date :defaultValue (:date item)}]])])

(defn component
  [item]
  (let [*date-visible? (r/atom (boolean (:date item)))]
    (reset! *related-items (into {}
                                 (map (fn [{:keys [id title]}] [id title]) (:related_items item))))
    (reset! *resource-links (mapv (fn [[k v]] {:id (random-uuid) :k (name k) :v v})
                              (or (get-in item [:data :resource-links]) {})))
    (r/create-class {:component-did-mount #(.focus (get-title-el))
                     :reagent-render ;
                       (fn [_item] [:<>
                                    [:div.left-column [basic-elements-component item] [:hr]
                                     [resource-links-component item]]
                                    [:div.right-column [event-component item *date-visible?] [:hr]
                                     [link-context-item/component item]]])})))

(defn get-values
  [id _item?]
  {:context {:id id
             :title (.-value (get-title-el))
             :short_title (.-value (get-short-title-el))
             :sort_idx (utils/display->sort-idx (.-value (get-sort-idx-el)))
             :annotation (.-value (get-annotation-el))
             :tags (.-value (get-tags-el))
             :has-event? (.-checked (get-event-el))
             :date (when (get-date-el) (.-value (get-date-el)))
             :data {:highlighted-secondary-contexts
                      (str/split (.-value (get-highlighted-secondary-contexts-el)) #" ")
                    :resource-links (into {}
                                          (keep (fn [{:keys [k v]}]
                                                  (when (and (not-empty k) (not-empty v))
                                                    [(keyword k) v]))
                                                @*resource-links))}}})
