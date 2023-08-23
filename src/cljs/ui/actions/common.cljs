(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api
            utils))

(defn save-input! [*state]
  (swap! *state assoc :loading false))

(def save-input-debounced!
  (utils/debounce save-input! 500))

(defn reset-state! [new-state *state]
  (reset! *state (dissoc new-state :enter-pressed?)))

(defn- update-state [{:keys [issues contexts] :as i} 
                     state]
  (merge 
   state
   i
   {:issues (if issues 
              (first issues)
              (:issues state))
    :aggregated-contexts (if issues
                           (second issues)
                           (:aggregated-contexts state))
    :contexts (or contexts (:contexts state))}))

(defn- list-resources [state]
  (api/list-resources (dissoc state :issues :contexts)))

(defn- fetch-resources
  [state]
  (go (-> state
          list-resources
          <p!
          (update-state state))))

(defn- dissoc-loading [_ *state]
  (save-input-debounced! *state))

(defn fetch-and-reset!
  [*state state]
  (let [state (assoc state :loading true :preview-issue nil)]
    (reset! *state state)
    (go (-> state
            fetch-resources
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))

(defn- fetch-resources-with-method
  [state method & args]
  (go (-> (apply method state args)
          <p!
          (update-state state))))

(defn fetch-and-reset-with-method!
  [*state state method & args]
  (let [state (assoc state :loading true :preview-issue nil)]
    (reset! *state state)
    (go (-> (apply fetch-resources-with-method state method args)
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))
