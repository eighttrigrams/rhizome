(ns ui.modals.annotation-edit
  "The modal the card in the list opens: one relation, edited where it is shown.

   It began as the annotation editor and still carries the annotations -- the
   item's own subtitle and the one on this edge -- but what it edits is the
   edge. The badge, the part-of tick and the sibling index are the same three
   controls the edit modal offers for every relation of the selected item (see
   ui.modals.link-context-item); here they are offered for the one edge the card
   is standing on, which is the only place they can be reached for a row that is
   not the selection."
  (:require [reagent.core :as r]
            [ui.modals.link-context-item :as link-context-item]
            [ui.refusal :as refusal]))

(def *fields
  "Everything the modal is holding, and what the save is read off. At the ns
   level rather than in the component, because get-values is called from the key
   handler, which is outside it -- the arrangement link-context-item already
   has, and for the same reason.

   Read off here and not off the DOM, unlike the fields the edit modal reads
   back at save time. Those are uncontrolled and the DOM is where they live;
   these are controlled, so the element trails the atom by a render, and a save
   in that window -- a keystroke away from a click, which is a whole scenario in
   the e2e suite -- would send what the modal said one frame ago."
  (r/atom {}))

(defn- standing-of
  "What the row already says about the edge it is shown by: the contexts mirror
   it carries, at the whole the card was filed under (ui.actions/filed-under).
   The edit modal reads the very same map for the selected item's relations."
  [item selected-context]
  (or (get-in item [:data :contexts (:id selected-context)]) {}))

(defn- reset-fields!
  [item selected-context]
  (let [item-data (when item (or (:data item) {}))
        standing (standing-of item selected-context)]
    (reset! *fields
      {:global-annotation (or (:annotation item-data) "")
       :relation-annotation (or (:annotation item) "")
       ;; nil is not `no badge`: the mirror only carries the flag once something
       ;; has written it, and the column it mirrors defaults to shown. Reading a
       ;; missing flag as false would put the modal in front of the user with
       ;; the badge already unticked and take it away on the next save.
       :show-badge? (not (false? (:show-badge? standing)))
       :is-part-of? (boolean (:is-part-of? standing))
       :part-of-sort-idx (link-context-item/part-of-idx->display
                           (or (:part-of-sort-idx standing) -1))})))

(defn get-values
  [item selected-context]
  (let [{:keys [global-annotation relation-annotation show-badge? is-part-of? part-of-sort-idx]}
          @*fields]
    (cond-> {:item-id (:id item)
             :context-id (when selected-context (:id selected-context))
             :global-annotation global-annotation
             :relation-annotation (when selected-context relation-annotation)}
      ;; The standing travels only when the modal actually offered it. In the
      ;; overview there is no edge under the card at all, and a map sent from
      ;; there would ask the backend to write the standing of a relation nobody
      ;; edited.
      selected-context (assoc :relation-standing
                         {:show-badge? show-badge?
                          :is-part-of? is-part-of?
                          ;; Parsed here and once: holding what was typed rather
                          ;; than what it parses to is what lets a half-typed
                          ;; number be a half-typed number and not a NaN.
                          :part-of-sort-idx (link-context-item/display->part-of-idx
                                              part-of-sort-idx)}))))

(defn- notice-component
  "Why the save did not go through. Either way it wrote nothing -- the standing
   is written before the two annotations, so a refusal there leaves them
   unwritten too -- and the modal is still the one the user was typing in."
  [{:keys [kind message]}]
  [refusal/component message
   (case kind
     :refused "Nothing was saved. Untick \"part of\", or place it elsewhere, and save again."
     "Nothing was saved. Everything you typed is still here — try saving again.")])

(defn- field
  [k]
  #(swap! *fields assoc k (.-value (.-target %))))

(defn- flag
  [k]
  #(swap! *fields assoc k (.-checked (.-target %))))

(defn- relation-standing-component
  "The three controls the edge itself carries. The part-of pair is the edit
   modal's, class names and all, so the one set of rules in main.css describes
   both; the row they sit in is this modal's own, because here there is no
   relation title above them and no next relation below."
  []
  [:div.relation-standing
   [:label {:title "Show a badge for this relation"}
    [:input#relation-show-badge-input
     {:type :checkbox :checked (:show-badge? @*fields) :on-change (flag :show-badge?)}]
    " badge"]
   [:div.relation-part-of
    [:label {:title "This context is the whole; this item is one of its parts"}
     [:input#relation-part-of-input
      {:type :checkbox :checked (:is-part-of? @*fields) :on-change (flag :is-part-of?)}]
     " part of"]
    [:input.relation-sort-idx
     {:id "relation-part-of-sort-idx-input"
      :autoComplete :off
      :title "Position among the parts of this whole -- a number; left empty it sorts last"
      :value (:part-of-sort-idx @*fields)
      :on-change (field :part-of-sort-idx)
      :placeholder "idx"}]]])

(defn component
  [item selected-context notice]
  ;; A notice means the save did not go through and the modal is still the one
  ;; the user was typing in, so it must not be reset from the stored item. This
  ;; runs once per mount, and the modal is mounted afresh every time a card is
  ;; clicked -- the layer it lives in is emptied when :modal goes nil.
  (when-not notice (reset-fields! item selected-context))
  (r/create-class
    {:component-did-update (fn [this [_ old-item _old-selected-context]]
                             (let [[_ new-item new-selected-context] (r/argv this)]
                               (when (not= (:id old-item) (:id new-item))
                                 (reset-fields! new-item new-selected-context))))
     :reagent-render ;
       (fn [_item selected-context notice]
         (let [in-overview? (nil? selected-context)]
           [:div [:h3 "Edit Relation"]
            ;; The notice sits in a slot that is always there, empty or not --
            ;; see the same arrangement in ui.modals.link-context-item, and why
            ;; it is not a sibling that comes and goes.
            [:div (when notice [notice-component notice])]
            [:input#global-annotation-input.line
             {:type "text"
              :value (:global-annotation @*fields)
              :auto-focus true
              :on-change (field :global-annotation)
              :placeholder "Global annotation (subtitle)..."}]
            (when-not in-overview?
              [:<>
               [:input#relation-annotation-input.line
                {:type "text"
                 :value (:relation-annotation @*fields)
                 :on-change (field :relation-annotation)
                 :placeholder (str "Relation annotation for " (:title selected-context) "...")}]
               [relation-standing-component]])
            [:p {:style {:font-size "0.9em" :color "#666" :margin-top "10px"}}
             "Press Alt+9 to save, ESC to cancel"]]))}))
