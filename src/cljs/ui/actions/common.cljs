(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api))

(defn reset-state! [new-state *state]
  (reset! *state new-state))

(defn- update-state [{:keys [issues contexts] :as i} 
                     state]
  (merge 
   state
   i
   {:issues (or issues (:issues state))
    :contexts (or contexts (:contexts state))}))

(defn- list-resources [state q]
  (api/list-resources
   (-> state
       (dissoc :issues :contexts)
       (assoc :q q))))

(defn- fetch-resources
  [state value]
  (go (-> state
          (list-resources value)
          <p!
          (update-state state))))

(defn fetch-and-reset!
  ([*state state] (fetch-and-reset! *state state ""))
  ([*state state value]
   (go (-> state
           (fetch-resources value)
           <!
           (reset-state! *state)))))
