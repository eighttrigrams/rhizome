(ns ui.danger-mode
  (:require [reagent.core :as r]
            [ui.actions.common :refer [fetch-and-reset!]]))

(defn toggle!
  [*state]
  (swap! *state (fn [s]
                  (if (:danger-mode? s)
                    (-> s
                        (dissoc :danger-mode?)
                        (dissoc :danger-preview)
                        (cond-> (= :danger-confirm (:modal s)) (dissoc :modal)))
                    (assoc s :danger-mode? true)))))

(defn- open-confirm!
  [*state]
  (when-let [context-id (:id (:selected-item @*state))]
    (-> (js/fetch (str "/api/items/" context-id "/related/deletion-preview"))
        (.then (fn [^js resp] (.json resp)))
        (.then (fn [^js data]
                 (let [preview (js->clj data :keywordize-keys true)]
                   (swap! *state assoc
                     :danger-preview preview
                     :modal :danger-confirm)))))))

(defn- close-confirm!
  [*state]
  (swap! *state #(-> %
                     (dissoc :modal)
                     (dissoc :danger-preview))))

(defn- confirm-delete!
  [*state]
  (let [context-id (:id (:selected-item @*state))]
    (swap! *state #(-> %
                       (dissoc :modal)
                       (dissoc :danger-preview)))
    (-> (js/fetch (str "/api/items/" context-id "/related/delete")
                  #js {:method "POST"
                       :headers #js {"Content-Type" "application/json"}
                       :body (js/JSON.stringify
                               #js {:reason "in-app danger-mode bulk delete of related items"})})
        (.then (fn [^js resp] (.json resp)))
        (.then (fn [^js data]
                 (let [{:keys [dropped read-only-replica error]}
                         (js->clj data :keywordize-keys true)]
                   (cond
                     ;; A replica refuses the request outright (403), so the
                     ;; reason to report is the instance, not the gate.
                     read-only-replica (js/window.alert error)
                     dropped (js/window.alert "Recording mode is OFF — deletion was dropped.")))
                 (fetch-and-reset! *state (assoc @*state :q nil)))))))

(defn indicator
  [*state]
  (when (:danger-mode? @*state)
    ;; Not offered in hierarchy mode. The preview and the delete are stateless
    ;; REST calls that plan from the context's stored view; hierarchy mode is
    ;; session state on the client and cannot travel to them, so the planner
    ;; always runs the ordinary related-items query -- and the dialog's own
    ;; description, "items currently shown in this context", would be false. A
    ;; bulk delete may not run against a set the user cannot see. Teaching the
    ;; endpoint the mode would mean passing a view mode into a destructive REST
    ;; call for a mode that has no navigation yet; refusing is the smaller and
    ;; the truer answer.
    (let [hierarchy? (boolean (:hierarchy-mode? @*state))
          selected? (boolean (:selected-item @*state))
          offered? (and selected? (not hierarchy?))]
      [:div#danger-indicator
       {:style {:position "fixed"
                ;; Below the top strip, not over it: the strip takes a row of its
                ;; own (see layout.css) and these float over the app, which starts
                ;; underneath it. --top-strip-height is 0px when no strip is up,
                ;; so this is plain 6px the rest of the time.
                :top "calc(6px + var(--top-strip-height))"
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
        {:disabled (not offered?)
         :title (cond hierarchy?
                        (str "Not available in hierarchy mode — the deletion preview cannot see "
                             "the mode, so it would list items that are not on screen")
                      selected?
                        "Delete all related items of the currently selected item (q ignored)"
                      :else "Select an item first")
         :on-click #(when offered? (open-confirm! *state))
         :style {:background "white"
                 :color "#c0392b"
                 :border "none"
                 :padding "2px 6px"
                 :border-radius "3px"
                 :font-size "11px"
                 :font-weight "bold"
                 :cursor (if offered? "pointer" "not-allowed")
                 :opacity (if offered? 1 0.5)}}
        "Delete related items"]])))

(def ^:private keep-reason-label
  {"is-context-flag"   "marked as a context"
   "has-other-children" "owns other items"
   "has-other-inbound"  "still linked from elsewhere"})

(def ^:private skip-reason-label
  {"media-folder-missing"     "media folder offline"
   "multiple-file-references" "file shared by other items"
   "multiple-files-found"     "file in multiple folders"})

(defn- status-label
  "Friendly label for primary/cascade row. Items marked :deleted carry no
  reason; :skipped rows carry one of the file-safety reasons."
  [{:keys [status reason]}]
  (cond
    (= "deleted" status) nil
    (= "skipped" status) (str "skipped — "
                              (or (skip-reason-label reason) reason "unknown reason"))
    :else status))

(defn- section
  "Renders one labelled section of the confirm dialog. `desc` is the why-line
  shown under the heading so the user knows what this section means."
  [heading desc rows render-row]
  [:div {:style {:margin-bottom "12px"}}
   [:div {:style {:font-weight "bold" :margin-bottom "2px"}} heading]
   [:div {:style {:font-size "11px" :color "#666" :margin-bottom "4px"}} desc]
   (if (empty? rows)
     [:div {:style {:font-style "italic" :color "#999" :font-size "12px"
                    :margin-left "20px"}}
      "(none)"]
     [:ul {:style {:margin 0 :padding-left "20px"}}
      (map-indexed (fn [idx row] ^{:key (or (:id row) idx)} [render-row row])
                   rows)])])

(defn- primary-or-cascade-row
  [{:keys [title status] :as row}]
  (let [skipped? (= "skipped" status)]
    [:li {:style {:color (if skipped? "#888" "inherit")
                  :text-decoration (when skipped? "line-through")}}
     title
     (when-let [lbl (status-label row)]
       [:span {:style {:font-size "10px" :margin-left "6px" :color "#888"}}
        (str "(" lbl ")")])]))

(defn- unlinked-row
  [{:keys [title keep-reasons unlinked-from]}]
  [:li
   title
   [:span {:style {:font-size "10px" :margin-left "6px" :color "#666"}}
    (str "kept — "
         (clojure.string/join ", "
                              (map #(keep-reason-label % %) keep-reasons))
         (when (seq unlinked-from)
           (str "; unlinks " (count unlinked-from)
                " relation" (when (not= 1 (count unlinked-from)) "s"))))]])

(defn- count-status
  [rows status]
  (count (filter #(= status (:status %)) rows)))

(defn- confirm-dialog
  [*state]
  (r/create-class
    {:component-did-mount
       (fn [_] (when-let [el (.getElementById js/document "danger-confirm-dialog")]
                 (.focus el)))
     :reagent-render
       (fn [*state]
         (let [preview (:danger-preview @*state)
               primary  (:primary preview)
               cascade  (:cascade preview)
               unlinked (:unlinked preview)
               n-primary-del  (count-status primary "deleted")
               n-cascade-del  (count-status cascade "deleted")
               n-primary-skip (count-status primary "skipped")
               n-cascade-skip (count-status cascade "skipped")
               n-unlink-only  (count unlinked)
               n-total-del    (+ n-primary-del n-cascade-del)
               can-confirm?   (or (pos? n-total-del) (pos? n-unlink-only))
               handle-keydown (fn [e]
                                (.stopPropagation e)
                                (let [code (.-code e)]
                                  (cond (= "Escape" code)
                                          (do (.preventDefault e)
                                              (close-confirm! *state))
                                        (= "Enter" code)
                                          (do (.preventDefault e)
                                              (when can-confirm?
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
                      :max-width "640px"
                      :max-height "80vh"
                      :display "flex"
                      :flex-direction "column"
                      :outline "none"}}
             [:h3 {:style {:margin-top 0 :color "#c0392b"}}
              (str "Delete " n-total-del " item" (when (not= n-total-del 1) "s")
                   (when (pos? n-unlink-only)
                     (str ", unlink " n-unlink-only " more"))
                   "?")]
             [:p {:style {:font-size "12px" :color "#444"}}
              "Recording mode must be ON for this to take effect."]
             [:div {:style {:overflow-y "auto" :flex 1}}
              [section
               (str "Will delete (in selection) — " n-primary-del
                    (when (pos? n-primary-skip) (str " (" n-primary-skip " skipped)")))
               "Items currently shown in this context, filtered by the active secondary contexts."
               primary
               primary-or-cascade-row]
              [section
               (str "Will also delete (became orphaned) — " n-cascade-del
                    (when (pos? n-cascade-skip) (str " (" n-cascade-skip " skipped)")))
               "Items that were only reachable through the above. They will be unlinked, found to have nothing else pointing at them, and removed."
               cascade
               primary-or-cascade-row]
              [section
               (str "Will unlink only (item is kept) — " n-unlink-only)
               "Items whose relations to the deleted items will be removed, but the items themselves stay — they are contexts or are still referenced from elsewhere."
               unlinked
               unlinked-row]]
             [:div {:style {:margin-top "12px"
                            :display "flex"
                            :gap "8px"
                            :justify-content "flex-end"}}
              [:button {:on-click #(close-confirm! *state)} "Cancel"]
              [:button {:on-click #(confirm-delete! *state)
                        :disabled (not can-confirm?)
                        :style {:background "#c0392b"
                                :color "white"
                                :border "none"
                                :padding "6px 12px"
                                :border-radius "3px"
                                :cursor (if can-confirm? "pointer" "not-allowed")}}
               (cond
                 (pos? n-total-del) (str "Delete " n-total-del)
                 (pos? n-unlink-only) "Unlink only"
                 :else "Nothing to do")]]]]))}))

(defn confirm-modal
  [*state]
  (when (= :danger-confirm (:modal @*state))
    [confirm-dialog *state]))
