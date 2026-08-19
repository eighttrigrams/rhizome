(ns ui.modals
  (:require [reagent.core :as r]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [ui.codemirror :as codemirror]
            [ui.modals.key-handler :as key-handler]
            [ui.modals.item-edit :as item-edit]
            [ui.modals.link-context-item :as link-context-item]
            [ui.modals.annotation-edit :as annotation-edit]
            [ui.modals.actions :as actions]
            api))

(def *original-description (r/atom nil))

(defn- get-description-el [] (.getElementById js/document "description-editor"))

(defn- codemirror-component
  [item]
  (let [*editor (r/atom nil)]
    (r/create-class {:component-did-mount
                       (fn []
                         (reset! *original-description (:description item))
                         (let [element (.getElementById js/document "description-editor")
                               editor (codemirror/create-editor
                                        element
                                        {:doc (:description item) :markdown? true :focus? true})]
                           (reset! *editor editor)))
                     :reagent-render (fn [_item] [:div#description-editor])})))

(defn- get-current-description
  []
  (let [el (.getElementById js/document "description-editor")
        codemirror-view (when el (.-__codemirror el))]
    (if codemirror-view (codemirror/get-editor-value codemirror-view) "")))

(defn- has-unsaved-changes? [] (not= (or @*original-description "") (get-current-description)))

(defn- confirm-discard-dialog
  [*state discard-fn]
  (let [handle-keydown (fn [e]
                         (.stopPropagation e)
                         (let [code (.-code e)]
                           (cond (= "Enter" code) (do (.preventDefault e) (discard-fn))
                                 (= "Escape" code) (do (.preventDefault e)
                                                       (swap! *state dissoc
                                                         :show-confirm-discard)))))]
    (r/create-class
      {:component-did-mount
         (fn [_] (when-let [el (.getElementById js/document "confirm-discard-dialog")] (.focus el)))
       :reagent-render (fn [_*state _discard-fn]
                         [:div#confirm-discard-dialog {:tab-index 0 :on-key-down handle-keydown}
                          [:div.confirm-dialog-content [:h3 "Unsaved Changes"]
                           [:p "You have unsaved changes. Discard them?"]
                           [:div.confirm-dialog-buttons [:button {:on-click discard-fn} "Discard"]
                            [:button {:on-click #(swap! *state dissoc :show-confirm-discard)}
                             "Cancel"]]]])})))

(defn- handle-description-escape
  [*state]
  (if (:show-confirm-discard @*state)
    nil
    (if (has-unsaved-changes?)
      (swap! *state assoc :show-confirm-discard true)
      (actions/cancel-modal! *state))))

(defn- handle-external-edit-escape
  [*state]
  (if (:show-confirm-discard @*state)
    nil
    (go (let [result (<p! (api/get-obsidian-file-content @*state))
              external-content (:obsidian-file-content result)
              saved-description (or (:description (:selected-item @*state)) "")]
          (if (= external-content saved-description)
            (actions/discard-obsidian-and-close! *state)
            (swap! *state assoc :show-confirm-discard true))))))

(defn- handle-keys
  [*state item]
  (case (:modal @*state)
    :edit-context (key-handler/handle-edit-keys *state
                                                #(item-edit/get-values (:id item)
                                                                       (:selected-item @*state))
                                                #(link-context-item/get-values))
    :description (key-handler/handle-description-keys
                   *state
                   #(do {:id (:id item) :description (get-current-description)})
                   #(handle-description-escape *state)
                   #(reset! *original-description (get-current-description)))
    ;; The context is the one settled when the modal was opened, off the row that
    ;; was clicked -- not the selected item, which below level 1 is not the whole
    ;; the row's annotation belongs to. See ui.actions/filed-under.
    :annotation-edit (key-handler/handle-modal-keys *state
                                                    #(annotation-edit/get-values
                                                       (:annotation-edit-item @*state)
                                                       (:annotation-edit-context @*state)))
    :external-edit (key-handler/handle-external-edit-keys *state
                                                          #(do {:id (:id item)})
                                                          #(handle-external-edit-escape *state))
    #()))

(defn- save-notice
  "Why the last save from a modal did not go through, or nil. The two cases read
   differently to the user -- one is theirs to correct, the other is theirs to
   retry -- so they arrive as separate keys and are told apart here rather than
   in the component.

   Read by both modals that can be refused: the edit modal, and the relation
   modal now that it writes a part-of edge too. One modal is open at a time and
   the keys are cleared on the way into every save, so the two cannot be looking
   at each other's refusal."
  [{:keys [part-of-refused save-failed]}]
  (cond part-of-refused {:kind :refused :message part-of-refused}
        save-failed {:kind :failed :message (str "The save failed: " save-failed)}))

(defn component
  [*state]
  (fn [_*state]
    (let [item (:selected-item @*state)]
      [:div {:on-key-down (handle-keys *state item) :on-click #(.stopPropagation %)}
       (case (:modal @*state)
         :description [:<> [codemirror-component item]
                       (when (:show-confirm-discard @*state)
                         [confirm-discard-dialog *state #(actions/cancel-modal! *state)])]
         :edit-context [:div#modal-component
                        [item-edit/component item (save-notice @*state)]]
         :annotation-edit [:div#modal-component
                           [annotation-edit/component (:annotation-edit-item @*state)
                            (:annotation-edit-context @*state) (save-notice @*state)]]
         :external-edit
           [:<>
            [:div#modal-component {:tabIndex 0 :autoFocus true} [:h3 "Editing in Obsidian"]
             [:p (str "Editing \"" (:title (:selected-item @*state)) "\" in Obsidian")]
             [:p "Press ESC to discard changes, or Alt+9 to sync changes back"]]
            (when (:show-confirm-discard @*state)
              [confirm-discard-dialog *state #(actions/discard-obsidian-and-close! *state)])]
         nil)])))
