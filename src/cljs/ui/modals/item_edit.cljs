(ns ui.modals.item-edit
  (:require [reagent.core :as r]
            [ui.codemirror :as codemirror]
            [ui.modals.link-context-item :as link-context-item]
            [clojure.string :as str]
            [utils :as utils]
            api))

(defn- get-title-el [] (.getElementById js/document "item-title"))

(defn- get-short-title-el [] (.getElementById js/document "item-short-title"))

(defn- get-human-readable-id-el []
  (.getElementById js/document "item-human-readable-id"))

(defn- get-annotation-el [] (.getElementById js/document "item-annotation"))

(defn- get-sort-idx-el [] (.getElementById js/document "item-sort-idx"))

(defn- get-tags-el [] (.getElementById js/document "item-tags"))

(defn- get-highlighted-secondary-contexts-el
  []
  (.getElementById js/document "item-highlighted-secondary-contexts"))

(defn- get-hide-in-global-search-el
  []
  (.getElementById js/document "item-hide-in-global-search"))

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

;; ---------------------------------------------------------------------------
;; The one-line editors
;;
;; The keyboard scheme reached three of these fields before -- title, short title
;; and tags -- through the hand-written editor that used to be vendored under
;; src/cljs/net/eighttrigrams. It is the library's input mode now (see
;; ui.codemirror), and it is on all seven, because there was never a reason for
;; the other four to be the odd ones out: they are the same `input.line` in the
;; same column, read back the same way at save time.

(defn- line-fields
  []
  [(get-title-el) (get-short-title-el) (get-human-readable-id-el) (get-annotation-el)
   (get-sort-idx-el) (get-tags-el) (get-highlighted-secondary-contexts-el)])

(defn- editor-on!
  "A one-line editor in front of field `el`, drawn in the div that wraps it.

   The wrapper is already there in the markup -- every one of these fields sits in
   its own [:div] -- and carries .line-host, whose CSS is the two properties the
   transparent input can no longer contribute to the flow. See main.css."
  [el]
  (codemirror/create-input-editor (.-parentElement el) el {}))

(def ^:private *editors (atom []))

(defn basic-elements-component
  [item]
  (r/create-class
    {:component-did-mount #(reset! *editors (mapv editor-on! (line-fields)))
     :component-will-unmount #(do (doseq [view @*editors] (.destroy view)) (reset! *editors []))
     :reagent-render ;
       (fn [_item]
         [:<> [:div {:style {:margin-bottom "10px"}} "id: " (:id item)]
          [:div.line-host
           [:input#item-title.line
            {:autoComplete :off :defaultValue (:title item) :placeholder "Title"}]]
          [:div.line-host
           [:input#item-short-title.line
            {:autoComplete :off :defaultValue (:short_title item) :placeholder "Short title"}]]
          [:div.line-host
           [:input#item-human-readable-id.line
            {:autoComplete :off
             :defaultValue (:human_readable_id item)
             :placeholder "Human-readable id (must contain a non-digit)"}]]
          [:div.line-host
           [:input#item-annotation.line
            {:autoComplete :off :defaultValue (:annotation item) :placeholder "Annotation"}]]
          [:div.line-host
           [:input#item-sort-idx.line
            {:autoComplete :off
             :defaultValue (utils/sort-idx->display (:sort_idx item))
             :placeholder "Sort index (number or roman numeral)"}]]
          [:div.line-host
           [:input#item-tags.line
            {:autoComplete :off :defaultValue (:tags item) :placeholder "Tags"}]]
          [:div.line-host
           [:input#item-highlighted-secondary-contexts.line
            {:autoComplete :off
             :defaultValue (str/join " " (:highlighted-secondary-contexts (:data item)))
             :placeholder "Highlighted secondary contexts"}]]
          (when (:is_context item)
            [:div {:style {:margin-top "10px"}}
             [:label
              [:input#item-hide-in-global-search
               {:type :checkbox
                :defaultChecked (boolean (:hide_in_global_search item))}]
              " Hide in global search"]])])}))

(defn event-component
  [item *date-visible?]
  [:div {:style {:display "flex" :align-items "center" :gap "10px"}} [:h4 "Event"]
   [:span "Has event?"]
   [:input#has-date
    {:type :checkbox :defaultChecked @*date-visible? :on-click #(swap! *date-visible? not)}]
   (when @*date-visible?
     [:<> [:span "Event"] [:input#date-picker {:type :date :defaultValue (:date item)}]])])

(defn component
  [item notice]
  (let [*date-visible? (r/atom (boolean (:date item)))]
    (reset! *related-items (into {}
                                 (map (fn [{:keys [id title]}] [id title]) (:related_items item))))
    (reset! *resource-links (mapv (fn [[k v]] {:id (random-uuid) :k (name k) :v v})
                              (or (get-in item [:data :resource-links]) {})))
    (r/create-class {:component-did-mount #(codemirror/focus-field! (get-title-el))
                     :reagent-render ;
                       (fn [_item notice]
                         [:<>
                          [:div.left-column [basic-elements-component item] [:hr]
                           [resource-links-component item]]
                          [:div.right-column [event-component item *date-visible?] [:hr]
                           [link-context-item/component item notice]]])})))

(defn get-values
  [id _item?]
  {:context (cond-> {:id id
                     :title (.-value (get-title-el))
                     :short_title (.-value (get-short-title-el))
                     :human_readable_id (.-value (get-human-readable-id-el))
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
                                                        @*resource-links))}}
              (get-hide-in-global-search-el)
              (assoc :hide_in_global_search
                (.-checked (get-hide-in-global-search-el))))})
