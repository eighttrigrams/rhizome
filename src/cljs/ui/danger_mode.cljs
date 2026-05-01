(ns ui.danger-mode
  (:require [reagent.core :as r]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [ui.actions.common :refer [fetch-and-reset-with-method-2!]]
            api))

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
  (when (:selected-item @*state)
    (go (let [resp (<p! (api/preview-deletion-of-related-items @*state))
              items (or (:danger-preview-items resp) [])]
          (swap! *state assoc
            :danger-preview-items items
            :modal :danger-confirm)))))

(defn- close-confirm!
  [*state]
  (swap! *state #(-> %
                     (dissoc :modal)
                     (dissoc :danger-preview-items))))

(defn- confirm-delete!
  [*state]
  (let [s (-> @*state
              (dissoc :modal)
              (dissoc :danger-preview-items))]
    (reset! *state s)
    (fetch-and-reset-with-method-2! *state api/delete-related-items)))

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
               n (count items)
               handle-keydown (fn [e]
                                (.stopPropagation e)
                                (let [code (.-code e)]
                                  (cond (= "Escape" code)
                                          (do (.preventDefault e)
                                              (close-confirm! *state))
                                        (= "Enter" code)
                                          (do (.preventDefault e)
                                              (when (pos? n)
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
              (str "Delete " n " related item" (when (not= n 1) "s") "?")]
             [:p "The following items will be permanently deleted:"]
             [:ul {:style {:overflow-y "auto"
                           :flex 1
                           :margin 0
                           :padding-left "20px"}}
              (map-indexed
                (fn [idx item]
                  ^{:key (or (:id item) idx)}
                  [:li (:title item)])
                items)]
             [:div {:style {:margin-top "16px"
                            :display "flex"
                            :gap "8px"
                            :justify-content "flex-end"}}
              [:button {:on-click #(close-confirm! *state)} "Cancel"]
              [:button {:on-click #(confirm-delete! *state)
                        :disabled (zero? n)
                        :style {:background "#c0392b"
                                :color "white"
                                :border "none"
                                :padding "6px 12px"
                                :border-radius "3px"
                                :cursor (if (zero? n) "not-allowed" "pointer")}}
               (str "Delete " n)]]]]))}))

(defn confirm-modal
  [*state]
  (when (= :danger-confirm (:modal @*state))
    [confirm-dialog *state]))
