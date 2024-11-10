(ns ui.modals
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.modals.key-handler :as key-handler]
            [ui.modals.issue-edit :as issue-edit]
            [ui.modals.link-context-issue :as link-context-issue]))

(defn- get-description-el []
  (.getElementById js/document "description-editor"))

(defn- textarea-component [_item]
  (r/create-class
   {:component-did-mount ;
    #(let [el (get-description-el)]
       (editor/create el {})
       (.focus el))
    :reagent-render (fn [item]
                      [:textarea#description-editor
                       {:defaultValue (:description item)
                        :spellCheck false}])}))

(defn- handle-keys [*state item]
  (case (:modal @*state)
    :edit-context
    (key-handler/handle-edit-keys *state
                                  #(issue-edit/get-values (:id item) 
                                                          (:selected-context @*state))
                                  #(link-context-issue/get-values))
    :description
    (key-handler/handle-modal-keys *state 
                                   #(do {:id          (:id item) 
                                         :description (.-value (get-description-el))}))
    #()))

(defn component [*state]
  (fn [_*state]
    (let [item (:selected-context @*state)]
      [:div
       {:on-key-down (handle-keys *state item)
        :on-click #(.stopPropagation %)}
       (case (:modal @*state)
         :description
         [textarea-component item]
         :edit-context
         [:div#modal-component [issue-edit/component item]]
         nil)])))
