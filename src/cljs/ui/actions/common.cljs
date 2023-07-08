(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api
            [goog.async.Debouncer]))

(defn debounce [f interval]
  (let [dbnc (goog.async.Debouncer. f interval)]
    (fn [& args] (.apply (.-fire dbnc) dbnc (to-array args)))))

(defn save-input! [*state]
  (swap! *state dissoc :loading))

(def save-input-debounced!
  (debounce save-input! 1500))

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
  (save-input-debounced! *state))

(defn fetch-and-reset!
  [*state state]
  (swap! *state assoc :loading true)
  (go (-> state
          fetch-resources
          <!
          (reset-state! *state)
          (dissoc-loading *state))))
