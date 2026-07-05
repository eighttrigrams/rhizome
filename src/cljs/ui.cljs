(ns ui
  (:require [reagent.core :as r]
            [ui.key-handler :as key-handler]
            [ui.main :as main]
            [ui.modals :as modals]
            [ui.modals.actions :as modal-actions]
            [ui.recording-mode :as recording-mode]
            [ui.danger-mode :as danger-mode]
            [ui.main.rhs.modifiers :as modifiers]))

(def original-state {:items [] :contexts [] :selected-item nil :active-search nil :modal nil})

(defn re-focus [] (when-let [el (.getElementById js/document "main-layer")] (.focus el)))

(defn- focus-modal-content
  [modal-type]
  (case modal-type
    :description (when-let [el (.getElementById js/document "description-editor")]
                   (when-let [cm (.-__codemirror el)]
                     (.focus cm)))
    :edit-context (when-let [el (.getElementById js/document "modal-component")]
                    (.focus el))
    :annotation-edit (when-let [el (.getElementById js/document "modal-component")]
                       (.focus el))
    :external-edit (when-let [el (.getElementById js/document "modal-component")]
                     (.focus el))
    nil))

(defn- handle-mask-keydown
  [*state e]
  (.stopPropagation e)
  (let [modal-type (:modal @*state)]
    (when (= "Escape" (.-code e))
      (if (#{:description :external-edit} modal-type)
        (focus-modal-content modal-type)
        (do (.preventDefault e)
            (modal-actions/cancel-modal! *state))))))

(defn- add-state-watch
  [*state]
  (add-watch *state
             :on-state-change
             (fn [_ _ old-state new-state]
               (when (or (and (:active-search old-state) (not (:active-search new-state)))
                         (and (:modal old-state) ;; TODO extract duplicate pattern
                              (not (:modal new-state))))
                 (re-focus)
                 (swap! *state dissoc :q))
               (when (and (:active-search old-state) (not (:active-search new-state)))
                 (swap! *state dissoc :vector-mode :vector-threshold
                        :vector-max-similarity :vector-min-similarity)))))

(defn component
  []
  (let [*state (r/atom original-state)]
    (add-state-watch *state)
    (r/create-class
      {:component-did-mount (fn [] (re-focus))
       :render ;
         (fn [] [:div#ui
                 {:on-mouse-leave #(reset! modifiers/*alt-pressed? false)
                  :on-mouse-enter #(reset! modifiers/*alt-pressed? false)}
                 [recording-mode/indicator *state]
                 [danger-mode/indicator *state]
                 [danger-mode/confirm-modal *state]
                 [:div#main-layer
                  {;; TODO document recipe to make the div able to listen to key events,
                   ;; https://stackoverflow.com/a/3149416
                   :tabIndex 0
                   :on-key-up #(when true (reset! modifiers/*alt-pressed? false))
                   :on-key-down #(do (when (.-altKey %) (reset! modifiers/*alt-pressed? true))
                                     ((key-handler/handle-keys *state) %))} [main/component *state]]
                 [:div#modals-layer
                  (when (:modal @*state)
                    [:<>
                     [:div.mask
                      {:tabIndex 0
                       :on-key-down #(handle-mask-keydown *state %)
                       :on-click #(focus-modal-content (:modal @*state))}]
                     [:div.modal-content-wrapper
                      {:class (when (= :description (:modal @*state)) "description-modal")}
                      [:div#modals-component
                       {:tabIndex 0
                        :on-key-down #(handle-mask-keydown *state %)}
                       [modals/component *state]]]])]])})))
