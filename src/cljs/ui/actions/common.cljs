(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api))

(defn reset-state! [new-state *state]
  (reset! *state (assoc new-state :loading true)))

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
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn fetch-and-reset!
  ([*state state]
   (go (-> state
           fetch-resources
           <!
           (reset-state! *state)
           (dissoc-loading *state)))))
