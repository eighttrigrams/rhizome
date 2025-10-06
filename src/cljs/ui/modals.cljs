(ns ui.modals
  (:require [reagent.core :as r]
            [ui.codemirror :as codemirror]
            [ui.modals.key-handler :as key-handler]
            [ui.modals.item-edit :as item-edit]
            [ui.modals.link-context-item :as link-context-item]))

(defn- get-description-el []
  (.getElementById js/document "description-editor"))

(defn- codemirror-component [item]
  (let [*editor (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn []
        (let [element (.getElementById js/document "description-editor")
              editor (codemirror/create-editor element {:doc (:description item)
                                                       :markdown? true
                                                       :focus? true})]
          (reset! *editor editor)))
      
      :reagent-render
      (fn [_item]
        [:div#description-editor])})))

(defn- handle-keys [*state item]
  (case (:modal @*state)
    :edit-context
    (key-handler/handle-edit-keys *state
                                  #(item-edit/get-values (:id item) 
                                                          (:selected-item @*state))
                                  #(link-context-item/get-values))
    :description
    (key-handler/handle-modal-keys *state 
                                   #(do {:id          (:id item) 
                                         :description (let [el (.getElementById js/document "description-editor")
                                                           codemirror-view (.-__codemirror el)]
                                                       (if codemirror-view
                                                         (codemirror/get-editor-value codemirror-view)
                                                         ""))}))
    :external-edit
    (key-handler/handle-modal-keys *state #(do {:id (:id item)}))
    #()))

(defn component [*state]
  (fn [_*state]
    (let [item (:selected-item @*state)]
      [:div
       {:on-key-down (handle-keys *state item)
        :on-click #(.stopPropagation %)}
       (case (:modal @*state)
         :description
         [codemirror-component item]
         :edit-context
         [:div#modal-component [item-edit/component item]]
         :external-edit
         [:div#modal-component
          {:tabIndex 0
           :autoFocus true}
          [:h3 "Editing in Obsidian"]
          [:p (str "Editing \"" (:title (:selected-item @*state)) "\" in Obsidian")]
          [:p "Press ESC to discard changes, or Alt+9 to sync changes back"]]
         nil)])))
