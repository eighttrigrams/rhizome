(ns ui.danger-mode
  (:require [reagent.core :as r]
            [ui.actions.common :refer [fetch-and-reset!]]))

(defn toggle!
  [*state]
  (swap! *state (fn [s]
                  (if (:danger-mode? s)
                    (-> s
                        (dissoc :danger-mode?)
                        (dissoc :danger-preview-items)
                        (cond-> (= :danger-confirm (:modal s)) (dissoc :modal)))
                    (assoc s :danger-mode? true)))))

(defn- open-confirm!
  [*state]
  (when-let [parent-id (:id (:selected-item @*state))]
    (-> (js/fetch (str "/rest/items/" parent-id "/related/deletion-preview"))
        (.then (fn [^js resp] (.json resp)))
        (.then (fn [^js data]
                 (let [results (js->clj (.-results data) :keywordize-keys true)]
                   (swap! *state assoc
                     :danger-preview-items results
                     :modal :danger-confirm)))))))

(defn- close-confirm!
  [*state]
  (swap! *state #(-> %
                     (dissoc :modal)
                     (dissoc :danger-preview-items))))

(defn- confirm-delete!
  [*state]
  (let [parent-id (:id (:selected-item @*state))]
    (swap! *state #(-> %
                       (dissoc :modal)
                       (dissoc :danger-preview-items)))
    (-> (js/fetch (str "/rest/items/" parent-id "/related/delete")
                  #js {:method "POST"
                       :headers #js {"Content-Type" "application/json"}
                       :body (js/JSON.stringify
                               #js {:reason "in-app danger-mode bulk delete of related items"})})
        (.then (fn [^js resp] (.json resp)))
        (.then (fn [^js data]
                 (when (.-dropped data)
                   (js/window.alert
                     "Recording mode is OFF — deletion was dropped."))
                 (fetch-and-reset! *state (assoc @*state :q nil)))))))

(defn indicator
  [*state]
  (when (:danger-mode? @*state)
    (let [selected? (boolean (:selected-item @*state))]
      [:div#danger-indicator
       {:style {:position "fixed"
                :top "6px"
                :right "6px"
                :z-index 10000
                :background "#c0392b"
                :color "white"
                :padding "4px 8px"
                :border-radius "4px"
                :font-size "12px"
                :font-weight "bold"
                :box-shadow "0 1px 3px rgba(0,0,0,0.3)"
                :user-select "none"
                :display "flex"
                :gap "8px"
                :align-items "center"}}
       [:span {:title "Danger mode — destructive actions enabled"} "⚠ DANGER"]
       [:button
        {:disabled (not selected?)
         :title (if selected?
                  "Delete all related items of the currently selected item (q ignored)"
                  "Select an item first")
         :on-click #(open-confirm! *state)
         :style {:background "white"
                 :color "#c0392b"
                 :border "none"
                 :padding "2px 6px"
                 :border-radius "3px"
                 :font-size "11px"
                 :font-weight "bold"
                 :cursor (if selected? "pointer" "not-allowed")
                 :opacity (if selected? 1 0.5)}}
        "Delete related items"]])))

(defn- confirm-dialog
  [*state]
  (r/create-class
    {:component-did-mount
       (fn [_] (when-let [el (.getElementById js/document "danger-confirm-dialog")]
                 (.focus el)))
     :reagent-render
       (fn [*state]
         (let [items (:danger-preview-items @*state)
               will-delete (filter #(= "deleted" (:status %)) items)
               will-skip (filter #(= "skipped" (:status %)) items)
               n-del (count will-delete)
               n-skip (count will-skip)
               handle-keydown (fn [e]
                                (.stopPropagation e)
                                (let [code (.-code e)]
                                  (cond (= "Escape" code)
                                          (do (.preventDefault e)
                                              (close-confirm! *state))
                                        (= "Enter" code)
                                          (do (.preventDefault e)
                                              (when (pos? n-del)
                                                (confirm-delete! *state))))))]
           [:div#danger-confirm-mask
            {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :z-index 10001
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"}}
            [:div#danger-confirm-dialog
             {:tab-index 0
              :on-key-down handle-keydown
              :style {:background "white"
                      :border "2px solid #c0392b"
                      :border-radius "6px"
                      :padding "16px"
                      :max-width "600px"
                      :max-height "80vh"
                      :display "flex"
                      :flex-direction "column"
                      :outline "none"}}
             [:h3 {:style {:margin-top 0 :color "#c0392b"}}
              (str "Delete " n-del " related item" (when (not= n-del 1) "s") "?"
                   (when (pos? n-skip)
                     (str " (" n-skip " will be skipped)")))]
             [:p "Recording mode must be ON for the deletion to take effect."]
             [:ul {:style {:overflow-y "auto"
                           :flex 1
                           :margin 0
                           :padding-left "20px"}}
              (map-indexed
                (fn [idx item]
                  (let [skipped? (= "skipped" (:status item))]
                    ^{:key (or (:id item) idx)}
                    [:li {:style {:color (if skipped? "#888" "inherit")
                                  :text-decoration (when skipped? "line-through")}}
                     (:title item)
                     (when skipped?
                       [:span {:style {:font-size "10px" :margin-left "6px"}}
                        (str "(skip: " (:reason item) ")")])]))
                items)]
             [:div {:style {:margin-top "16px"
                            :display "flex"
                            :gap "8px"
                            :justify-content "flex-end"}}
              [:button {:on-click #(close-confirm! *state)} "Cancel"]
              [:button {:on-click #(confirm-delete! *state)
                        :disabled (zero? n-del)
                        :style {:background "#c0392b"
                                :color "white"
                                :border "none"
                                :padding "6px 12px"
                                :border-radius "3px"
                                :cursor (if (zero? n-del) "not-allowed" "pointer")}}
               (str "Delete " n-del)]]]]))}))

(defn confirm-modal
  [*state]
  (when (= :danger-confirm (:modal @*state))
    [confirm-dialog *state]))
