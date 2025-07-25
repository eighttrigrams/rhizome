(ns ui.modals.item-edit
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.modals.link-context-item :as link-context-item]
            [clojure.string :as str]
            api))

(defn- get-title-el []
  (.getElementById js/document "item-title"))

(defn- get-short-title-el []
  (.getElementById js/document "item-short-title"))

(defn- get-annotation-el []
  (.getElementById js/document "item-annotation"))

(defn- get-sort-idx-el []
  (.getElementById js/document "item-sort-idx"))

(defn- get-tags-el []
  (.getElementById js/document "item-tags"))

(defn- get-highlighted-secondary-contexts-el []
  (.getElementById js/document "item-highlighted-secondary-contexts"))

(defn- get-event-el []
  (.getElementById js/document "has-date"))

(defn- get-date-el []
  (.getElementById js/document "date-picker"))

(def *related-items (r/atom {}))

(defn basic-elements-component [item]
  (r/create-class {:component-did-mount #(do (editor/create (get-title-el) {:input-field-mode? true})
                                             (editor/create (get-short-title-el) {:input-field-mode? true})
                                             (editor/create (get-tags-el) {:input-field-mode? true}))
                   :reagent-render ;
                   (fn [_item]
                     [:<>
                      [:div
                       [:input#item-title.line
                        {:autoComplete :off
                         :defaultValue (:title item)}]]
                      [:div
                       [:input#item-short-title.line
                        {:autoComplete :off
                         :defaultValue (:short_title item)}]]
                      [:div
                       [:input#item-annotation.line
                        {:autoComplete :off
                         :defaultValue (:annotation item)}]]
                      [:div
                       [:input#item-sort-idx.line
                        {:autoComplete :off
                         :defaultValue (:sort_idx item)}]]
                      [:div
                       [:input#item-tags.line
                        {:autoComplete :off
                         :defaultValue (:tags item)}]]
                      [:div
                       [:input#item-highlighted-secondary-contexts.line
                        {:autoComplete :off
                         :defaultValue (str/join " " (:highlighted-secondary-contexts
                                                      (:data item)))}]]
                      "id:" (:id item)])}))

(defn event-component [item *date-visible?]
  [:<>
   [:div
    [:p "Has event?"]
    [:input#has-date
     {:type           :checkbox
      :defaultChecked @*date-visible?
      :on-click       #(swap! *date-visible? not)}]]
   (when @*date-visible?
     [:<>
      [:p "Event"]
      [:input#date-picker
       {:type         :date
        :defaultValue (:date item)}]])])

(defn component [item]
  (let [*date-visible?  (r/atom (boolean (:date item)))]
    (reset! *related-items (into {} (map (fn [{:keys [id title]}] [id title]) (:related_items item))))
    (r/create-class
     {:component-did-mount #(.focus (get-title-el))
      :reagent-render      ;
      (fn [_item]
        [:<>
         [basic-elements-component item]
         [:hr]
         [:h4 "Event"]
         [event-component item *date-visible?]
         [:hr]
         [link-context-item/component item]])})))

(defn get-values [id _item?]
  {:context {:id          id
             :title       (.-value (get-title-el))
             :short_title (.-value (get-short-title-el))
             :sort_idx    (.-value (get-sort-idx-el))
             :annotation  (.-value (get-annotation-el))
             :tags        (.-value (get-tags-el))
             :has-event?  (.-checked (get-event-el))
             :date        (when (get-date-el) (.-value (get-date-el)))
             :data        {:highlighted-secondary-contexts (str/split (.-value (get-highlighted-secondary-contexts-el)) #" ")}}})
