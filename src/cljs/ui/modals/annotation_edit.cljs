(ns ui.modals.annotation-edit
  "The modal the card in the list opens: one relation, edited where it is shown.

   It began as the annotation editor and still carries the annotations -- the
   item's own subtitle and the one on this edge -- but what it edits is the
   edge. The badge, the part-of tick and the sibling index are the same three
   controls the edit modal offers for every relation of the selected item (see
   ui.modals.link-context-item); here they are offered for the one edge the card
   is standing on, which is the only place they can be reached for a row that is
   not the selection."
  (:require ["react-markdown$default" :as ReactMarkdown]
            [clojure.string :as str]
            [reagent.core :as r]
            [ui.codemirror :as codemirror]
            [ui.markdown :as markdown]
            [ui.main.provenance :as provenance]
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
       :relation-description nil
       ;; Absent for the same reason and by the same fetch: the versions arrive
       ;; with the text (repository/fetch-relation-history). Nil is what the
       ;; version bar draws as "Version 1 (current)" with both arrows dead, which
       ;; is what an unanswered history looks like from the outside.
       :versions nil
       ;; 0 is the current version, and stepping back only ever reads: see
       ;; past-version-component.
       :version-idx 0
       :pane nil
       :provenance nil})))

(defn- load-history!
  "Ask for the edge's body text and its versions, which are the one part of a
   relation that does not arrive with the row -- see
   repository/fetch-relation-history, and the pointer resting on the card's
   strip, which is the other gesture that asks for the text (and asks for nothing
   else, because it runs on hover).

   One fetch and not two. The bar has to know how many versions there are before
   it can say which one is on screen, so a modal that fetched the text now and the
   history on demand would either open with a bar that cannot be read or need two
   round trips to open at all.

   The answer is dropped unless the fields still belong to the edge it is about:
   this modal is opened by clicking a card, and another card can be clicked
   before the first answer lands."
  [*state item selected-context]
  (when selected-context
    (-> (api/fetch-relation-history @*state
                                    {:item-id (:id item) :context-id (:id selected-context)})
        (.then (fn [result]
                 (let [{:keys [item-id context-id text versions]} (:relation-history result)]
                   (swap! *fields
                     (fn [{:keys [edge] :as fields}]
                       (if (= edge [item-id context-id])
                         (assoc fields
                           :relation-description (or text "")
                           :versions (vec versions))
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
        ;; The editor when it is up, and what was loaded when it is not. Two
        ;; different absences: before the fetch lands there is nothing loaded
        ;; either and this stays nil, which is what stops the save writing; while
        ;; the bar is off the current version, or the provenance pane is open, the
        ;; editor is unmounted and this is the text it was holding -- see
        ;; switch-view!, which puts it there on the way out. So a save from a past
        ;; version rewrites the text that is standing, which is to say writes
        ;; nothing to it, rather than reviving the version being read.
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

(defn- switch-view!
  "Move the bar, or open and close a pane -- and keep what is in the editor.

   The editor is CodeMirror, it owns its own document, and every one of these
   moves unmounts it. An edit that had only ever been made in that document would
   go with it: back to the current version and the editor would be rebuilt from
   the text the fetch delivered, silently discarding what had been typed. So it is
   read back into *fields first, which is both where the save looks (get-values)
   and what the editor is remounted from."
  [changes]
  (let [typed (editor-value)]
    (swap! *fields
      (fn [fields]
        (merge (cond-> fields typed (assoc :relation-description typed)) changes)))))

(defn- load-provenance!
  "Who wrote each line of the text this edge is carrying now.

   Fetched when the pane is opened and not when the modal is, the arrangement
   ui.actions/open-provenance! has for an item: the assessment is not free (see
   provenance/of-relation) and nothing on the way in needs it.

   Fetched again on every open rather than kept, because the text under it can
   have moved in between -- the editor above is live, and a save closes the modal
   but a refused one does not. Dropped, like every other answer here, unless the
   fields still belong to the edge it is about."
  [*state item selected-context]
  (-> (api/fetch-relation-provenance @*state
                                     {:item-id (:id item) :context-id (:id selected-context)})
      (.then (fn [result]
               (let [{:keys [item-id context-id description caution]} (:relation-provenance result)]
                 (swap! *fields
                   (fn [{:keys [edge] :as fields}]
                     (if (= edge [item-id context-id])
                       (assoc fields :provenance {:description description :caution caution})
                       fields))))))))

(defn- version-label
  [versions idx]
  (if (seq versions)
    (let [{:keys [version source tombstone]} (nth versions idx nil)]
      (str "Version " (or version (inc idx))
           (when (zero? idx) " (current)")
           (when source (str " · " source))
           ;; The one version that no later text superseded: the relation was cut
           ;; here. Said in the bar and not only in the pane below, because the
           ;; bar is what a reader steps through, and a version that came about
           ;; differently from the rest should say so where the stepping happens.
           (when tombstone " · unlinked")))
    "Version 1 (current)"))

(defn- version-bar-component
  "The bar over a relation's text: the item's version bar, in a modal.

   The same two groups, saying the same two different things -- see
   ui.main.lhs.item-detail/version-navigation-controls, where the split is argued.
   Left is about a version: step through them and read which one is on screen and
   where it came from. Right is about the relation as such: who wrote the text
   that is standing, whichever version the arrows are pointing at.

   Here rather than as a page of its own, unlike the item's provenance, and not by
   preference: #modals-layer is a sibling of #main-layer and sits over it, so a
   page opened from inside a modal would be drawn behind it.

   No Diff button. The item's bar has one and this deliberately does not -- what
   was asked for is the older texts and their provenance, and a merge view inside a
   modal is a piece of work of its own rather than a smaller version of the two
   panes below."
  [*state item selected-context]
  (let [{:keys [versions version-idx pane]} @*fields
        idx (or version-idx 0)
        total (count versions)
        provenance? (= :provenance pane)]
    [:div.version-bar
     [:div.version-bar-group
      [:span.version-bar-scope "this version"]
      [:button.relation-version-back
       {:on-click #(switch-view! {:version-idx (inc idx)})
        :disabled (>= (inc idx) total)
        :title "The version before this one"} "←"]
      [:button.relation-version-forward
       {:on-click #(switch-view! {:version-idx (dec idx)})
        :disabled (<= idx 0)
        :title "The version after this one"} "→"]
      [:span.version-bar-label {:style {:font-weight "bold"}} (version-label versions idx)]]
     [:div.version-bar-group.version-bar-item-group
      [:span.version-bar-scope "this relation"]
      [:button.relation-provenance-open
       {:on-click (fn []
                    (if provenance?
                      (switch-view! {:pane nil})
                      (do (switch-view! {:pane :provenance :provenance nil})
                          (load-provenance! *state item selected-context))))
        :title "Who wrote each line of the text on this relation as it stands now"}
       (if provenance? "Close" "Provenance")]
      ;; Whole first, then the item: that is the direction the row is stored in
      ;; (relations.owner_id -> relations.target_id) and the direction the
      ;; sentence above the fields reads in.
      [:span.version-bar-item-id
       {:title "The whole this relation runs from, and the item it runs to"}
       (str "#" (:id selected-context) "→#" (:id item))]]]))

(defn- past-version-component
  "A version that is no longer the current one: rendered, and not editable.

   Rendered markdown rather than a second editor, because that is what an older
   version of an item's description is too -- the bar swaps the text under the
   detail view (ui.main.lhs.item-detail/component), which renders it. Reading a
   past version is reading, and it is not this modal's business to offer a save
   that would silently make the newest thing something old.

   Its element id is not the editor's, and that matters rather than merely being
   tidy: `editor-value` finds the editor by id, and a read-only view answering to
   that name would hand the save a text nobody typed."
  [{:keys [text tombstone]}]
  (let [blank? (str/blank? text)]
    [:div.relation-version-past
     (cond
       (and tombstone blank?)
       [:div.relation-version-note
        "The relation was unlinked here, and it was carrying no text."]
       tombstone
       [:div.relation-version-note
        (str "The relation was unlinked here. This is the text it went out on — "
             "step forward for what it carries now.")]
       blank?
       [:div.relation-version-note "This version of the text was empty."])
     (when-not blank?
       [:div.description
        [:> ReactMarkdown {:children text :components markdown/components}]])]))

(defn- provenance-pane-component
  "The provenance of the edge's text, in the view the item's own page draws it in
   -- ui.main.provenance/view, the same legend and the same spectrum. Two
   renderings of one number is how two surfaces come to disagree about it."
  []
  [:div.relation-provenance
   [provenance/view (:provenance @*fields)
    "Nothing is written on this relation, so there is nothing to attribute."]])

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
   the last 50px of it are off screen to begin with; the rest of the subtraction is
   that, plus this modal's padding, heading, two annotation lines, standing row,
   version bar and save hint. A flat 70vh put the hint just past the bottom edge.

   The number itself is in main.css, as --relation-text-height on
   #modal-component, because it is not only this box's: the two read-only panes
   that stand in this one's place have to be exactly as tall, or switching between
   them would walk the save hint up and down the modal. A custom property
   inherits, so the editor's own theme rule resolves it -- with the calc repeated
   as a fallback, because a height CodeMirror cannot resolve is the one-line-tall
   box described above rather than a visibly broken one."
  [doc]
  (r/create-class
    {:component-did-mount #(codemirror/create-editor
                             (editor-el)
                             {:doc doc
                              :markdown? true
                              :box {:height
                                      "var(--relation-text-height, calc(100vh - 350px))"}})
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
    {:component-did-mount (fn [] (when-not notice (load-history! *state item selected-context)))
     :component-did-update (fn [this [_ _old-state old-item _old-selected-context]]
                             (let [[_ _ new-item new-selected-context] (r/argv this)]
                               (when (not= (:id old-item) (:id new-item))
                                 (reset-fields! new-item new-selected-context)
                                 (load-history! *state new-item new-selected-context))))
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
               [version-bar-component *state item selected-context]
               (let [{:keys [relation-description versions version-idx pane]} @*fields
                     idx (or version-idx 0)]
                 (cond
                   ;; Until the fetch lands there is neither a text to edit nor a
                   ;; history to step through, and this stands in the box's place
                   ;; for that one round trip.
                   (nil? relation-description)
                     [:div.relation-description-loading "Loading the relation's text…"]
                   (= :provenance pane) [provenance-pane-component]
                   (pos? idx) [past-version-component (nth versions idx nil)]
                   :else ^{:key (pr-str (:edge @*fields))}
                         [description-editor-component relation-description]))])
            [:p {:style {:font-size "0.9em" :color "#666" :margin-top "10px"}}
             ;; What the hint says depends on what is in the box, because Alt+9
             ;; does not do the same thing in all three. Off the current version
             ;; the text on screen is not the one a save would write (get-values),
             ;; and saying "save" over it without saying that would read as an
             ;; offer to restore it.
             (let [{:keys [version-idx pane]} @*fields]
               (cond (= :provenance pane)
                       "Reading who wrote what. Alt+9 saves the relation, ESC cancels."
                     (pos? (or version-idx 0))
                       (str "An older version, read-only — step forward to the current one to"
                            " edit it. Alt+9 saves the relation, ESC cancels.")
                     :else "Press Alt+9 to save, ESC to cancel"))]]))}))
