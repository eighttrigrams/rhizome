(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api
            utils))

(defn save-input! [*state] (swap! *state assoc :loading false))

(def save-input-debounced! (utils/debounce save-input! 500))

(defn reset-state!
  [new-state *state]
  (when new-state ;; could be because ignore-item-description
    ;; Preserve modal state when resetting, but only if new-state doesn't explicitly close it
    (let [current-modal (:modal @*state)
          new-state-has-modal? (contains? new-state :modal)
          current-annotation-edit-item (:annotation-edit-item @*state)]
      (reset! *state (-> new-state
                         (dissoc :enter-pressed?)
                         (cond-> (and current-modal (not new-state-has-modal?))
                                   (assoc :modal current-modal)
                                 current-annotation-edit-item
                                   (assoc :annotation-edit-item current-annotation-edit-item)))))))

(defn- update-state
  [{:keys [items contexts aggregated-contexts item-description ignore-item-description] :as i}
   state]
  (cond ignore-item-description nil
        item-description (let [state (if (map? state) state @state)
                               state (assoc-in state [:preview-item :description] item-description)]
                           state)
        :else (merge (if (map? state) state @state)
                     i
                     {:items (if items items (:items state))
                      :contexts (or contexts (:contexts state))}
                     (when aggregated-contexts {:aggregated-contexts aggregated-contexts}))))

(defn- list-resources [state] (api/list-resources (dissoc state :items :contexts)))

(defn- fetch-resources
  [state]
  (go (-> state
          list-resources
          <p!
          (update-state state))))

(defn- dissoc-loading [_ *state] (save-input-debounced! *state))

(defn fetch-and-reset!
  [*state state]
  (let [state (assoc state
                :loading true
                :preview-item nil)]
    (reset! *state state)
    (go (-> state
            fetch-resources
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))

(defn- fetch-resources-with-method
  [state method & args]
  (go (-> (apply method (if (map? state) state @state) args)
          <p!
          (update-state state))))

(defn fetch-and-reset-with-method!
  [*state state method & args]
  (let [state''' (if (map? state) state @state)
        state' (assoc state'''
                 :loading (if (= :dont-reset-preview-item (last args)) (:loading state''') true)
                 :preview-item
                   (if (= :dont-reset-preview-item (last args)) (:preview-item state''') nil))]
    (reset! *state state')
    (go (-> (apply fetch-resources-with-method
                   (if (map? state) state' state)
                   method
                   (if (= :dont-reset-preview-item (last args)) (drop-last args) args))
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))







;; -------------------------
;; all of this is for fetch-item or fetch-last context, where aggregated-items are fetched
;; separately
;; for quicker response times

(defn- update-state-2 [new-state *state] (reset! *state (merge @*state new-state)))

(defn- fetch-resources-with-method-2
  [*state method & args]
  (go (-> (apply method @*state args)
          <p!
          (update-state-2 *state))))

(defn fetch-and-reset-with-method-2!
  [*state method & args]
  (go (-> (apply fetch-resources-with-method-2 *state method args)
          <!
          ((fn [_]
             (when (:selected-item @*state)
               (go (-> (api/fetch-aggregated-contexts @*state)
                       <p!
                       (#(swap! *state assoc :aggregated-contexts %))))))))))
