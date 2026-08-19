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
            [ui.codemirror :as codemirror]
            [ui.modals.link-context-item :as link-context-item]
            [ui.refusal :as refusal]
            api))

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
      {:edge [(:id item) (:id selected-context)]
       :global-annotation (or (:annotation item-data) "")
       :relation-annotation (or (:annotation item) "")
       ;; Read exactly the way the badge itself is read, nil and missing entry
       ;; included: ui.main.context-badges draws a badge only for an entry that
       ;; says so, so an item whose mirror has no entry for this whole -- which
       ;; is the shape a relation row written without its mirror leaves, see
       ;; dev-seed -- shows no badge, and the modal has to say the same. It said
       ;; the opposite while it defaulted a missing flag to the column's default
       ;; of shown: ticked, over a card carrying no badge. Ticking it here is
       ;; then what puts the badge there, and writes the missing entry with it.
       :show-badge? (boolean (:show-badge? standing))
       :is-part-of? (boolean (:is-part-of? standing))
       :part-of-sort-idx (link-context-item/part-of-idx->display
                           (or (:part-of-sort-idx standing) -1))
       ;; Absent, not empty. The body text is the one field of a relation that
       ;; does not travel with the row (see repository/fetch-relation-description),
       ;; so opening the modal is where it is asked for, and until the answer
       ;; lands there is nothing here that could be saved -- which is what
       ;; get-values reads this absence as.
       :relation-description nil})))

(defn- load-description!
  "Ask for the edge's body text, which is the one field of a relation that does
   not arrive with the row -- see repository/fetch-relation-description, and the
   pointer resting on the card's strip, which is the other gesture that asks for
   it.

   The answer is dropped unless the fields still belong to the edge it is about:
   this modal is opened by clicking a card, and another card can be clicked
   before the first answer lands."
  [*state item selected-context]
  (when selected-context
    (-> (api/fetch-relation-description @*state
                                        {:item-id (:id item) :context-id (:id selected-context)})
        (.then (fn [result]
                 (let [{:keys [item-id context-id text]} (:relation-description result)]
                   (swap! *fields
                     (fn [{:keys [edge] :as fields}]
                       (if (= edge [item-id context-id])
                         (assoc fields :relation-description (or text ""))
                         fields)))))))))

(defn- editor-el [] (.getElementById js/document "relation-description-editor"))

(defn- editor-value
  "What the editor is holding, or nil while it is not mounted.

   Read off the view rather than out of *fields, unlike every other field here:
   CodeMirror owns its own DOM and there is no controlled-input reconciliation to
   trail behind it, which is what made the DOM the wrong place to read the others
   from. ui.modals/get-current-description reads the description modal's editor
   the same way, off the same __codemirror handle."
  []
  (when-let [el (editor-el)]
    (when-let [view (.-__codemirror el)] (codemirror/get-editor-value view))))

(defn get-values
  [item selected-context]
  (let [{:keys [global-annotation relation-annotation relation-description show-badge? is-part-of?
                part-of-sort-idx]}
          @*fields
        ;; The editor when it is up, and the loaded text when it is not -- which
        ;; is to say nil, since nothing is loaded then either.
        relation-description (or (editor-value) relation-description)]
    (cond-> {:item-id (:id item)
             :context-id (when selected-context (:id selected-context))
             :global-annotation global-annotation
             :relation-annotation (when selected-context relation-annotation)}
      ;; Only once it has been loaded. A save while the textarea is still filling
      ;; would otherwise write its emptiness over whatever is stored -- the one
      ;; way a lazily loaded field can lose text, and the reason the textarea is
      ;; disabled until then rather than merely blank.
      (and selected-context (some? relation-description))
        (assoc :relation-description relation-description)
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

(defn- description-editor-component
  "The relation's text, in the editor the description modal uses -- the same
   CodeMirror, the same markdown mode, the same keyboard scheme.

   Mounted with its document rather than created empty and filled, which is why
   it is a component of its own: the text arrives after the modal does, and
   CodeMirror is built from a state, not re-rendered from props. Not focused, for
   the same reason -- the answer lands while the user may already be typing in
   the annotation field above, and taking the cursor off them then would be the
   fetch interrupting them.

   Its box has a definite height and scrolls inside itself, the way the
   description modal's does. Definite and not a max: a box that grew with the text
   would move the save hint down the modal as you typed, and -- the part that is
   not a matter of taste -- CodeMirror only stretches its content and its gutter
   to a height it has actually been given, so under a mere min-height the editing
   surface is one line tall inside a box several lines high, with the rest of it
   dead space the cursor cannot be put into.

   The height is what is left rather than a flat 70vh, which is the same thing to
   about five percent and fits. #modal-component is 100vh tall at top: 50px, so
   the last 50px of it are off screen to begin with; the 300px is that, plus this
   modal's padding, heading, two annotation lines, standing row and save hint. A
   flat 70vh put the hint just past the bottom edge."
  [doc]
  (r/create-class
    {:component-did-mount #(codemirror/create-editor (editor-el)
                                                     {:doc doc
                                                      :markdown? true
                                                      :box {:height "calc(100vh - 300px)"}})
     :reagent-render (fn [_doc] [:div#relation-description-editor.relation-description])}))

(defn component
  [*state item selected-context notice]
  ;; A notice means the save did not go through and the modal is still the one
  ;; the user was typing in, so it must not be reset from the stored item -- nor
  ;; re-fetched, which would put the stored text back over the edit that was
  ;; refused. This runs once per mount, and the modal is mounted afresh every
  ;; time a card is clicked: the layer it lives in is emptied when :modal goes nil.
  (when-not notice (reset-fields! item selected-context))
  (r/create-class
    {:component-did-mount (fn [] (when-not notice (load-description! *state item selected-context)))
     :component-did-update (fn [this [_ _old-state old-item _old-selected-context]]
                             (let [[_ _ new-item new-selected-context] (r/argv this)]
                               (when (not= (:id old-item) (:id new-item))
                                 (reset-fields! new-item new-selected-context)
                                 (load-description! *state new-item new-selected-context))))
     :reagent-render ;
       (fn [_*state _item selected-context notice]
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
               [relation-standing-component]
               (let [description (:relation-description @*fields)]
                 (if (nil? description)
                   [:div.relation-description-loading "Loading the relation's text…"]
                   ^{:key (pr-str (:edge @*fields))}
                   [description-editor-component description]))])
            [:p {:style {:font-size "0.9em" :color "#666" :margin-top "10px"}}
             "Press Alt+9 to save, ESC to cancel"]]))}))
