(ns ui.modals
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            [ui.modals.key-handler :as key-handler]
            [ui.modals.issue-edit :as issue-edit]
            [ui.modals.context-edit :as context-edit]
            [ui.modals.link-context-issue :as link-context-issue]))

(defn- get-description-el []
  (.getElementById js/document "description-editor"))

(defn- get-title-el []
  (.getElementById js/document "title"))

(defn- textarea-component [_item]
  (r/create-class
   {:component-did-mount ;
    #(let [el (get-description-el)]
       (editor/create el {})
       (.focus el))
    :reagent-render (fn [item]
                      [:textarea#description-editor
                       {:defaultValue (:description item)}])}))

(defn- new-issue-component []
  (r/create-class
   {:component-did-mount #(let [el (get-title-el)]
                            (editor/create el {:input-field-mode? true})
                            (.focus el))
    :reagent-render      (fn []
                           [:input#title
                            {:autoComplete :off}])}))

(defn- new-context-component []
  (r/create-class
   {:component-did-mount #(let [el (get-title-el)]
                            (editor/create el {:input-field-mode? true})
                            (.focus el))
    :reagent-render      (fn []
                           [:input#title
                            {:autoComplete :off}])}))

(defn- handle-keys [*state item]
  (case (:modal @*state)
    (:edit-context :edit-issue)
    (key-handler/handle-edit-keys *state
                                  #((if (:selected-issue @*state)
                                      issue-edit/get-values
                                      context-edit/get-values)
                                    (:id item))
                                  (if (= :edit-issue (:modal @*state))
                                    #(link-context-issue/get-values)
                                    nil))
    :description
    (key-handler/handle-modal-keys *state 
                                   #(do {:id          (:id item) 
                                         :description (.-value (get-description-el))}))
    :new-context
    (key-handler/handle-modal-keys *state
                                   #(do {:title (.-value (get-title-el))}))
    #()))

(defn component [*state]
  (fn [_*state]
    (let [item (if (:selected-issue @*state)
                 (:selected-issue @*state)
                 (:selected-context @*state))]
      [:div
       {:on-key-down (handle-keys *state item)
        :on-click #(.stopPropagation %)}
       (case (:modal @*state)
         :description
         [textarea-component item]
         :new-issue
         [new-issue-component]
         :new-context
         [new-context-component]
         :edit-issue
         [:div#modal-component [issue-edit/component item]]
         :edit-context
         [:div#modal-component [context-edit/component item]]
         nil)])))
