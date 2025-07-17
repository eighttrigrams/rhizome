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
  (when new-state ;; could be because ignore-issue-description
    (reset! *state (dissoc new-state :enter-pressed?))))

(defn- update-state [{:keys [issues contexts aggregated-contexts issue-description ignore-issue-description] :as i} 
                     state]
  (cond ignore-issue-description
        nil
        issue-description 
        (let [state (if (map? state) state @state)
              state (assoc-in
                     state [:preview-issue :description] issue-description)]
          state)
        :else
        (merge 
         (if (map? state) state @state)
         i
         {:issues   (if (and issues (first issues)) 
                      (first issues)
                      (:issues state))
          :contexts (or contexts (:contexts state))}
         (when aggregated-contexts
           {:aggregated-contexts aggregated-contexts}))))

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
  (go (-> (apply method (if (map? state) state @state) args)
          <p!
          (update-state state))))

(defn fetch-and-reset-with-method!
  [*state state method & args]
  (let [state''' (if (map? state) 
                        state
                        @state)
        state' (assoc state'''
                :loading (if (= :dont-reset-preview-issue (last args))
                           (:loading state''')
                           true)
                :preview-issue (if (= :dont-reset-preview-issue (last args))
                                 (:preview-issue state''')
                                 nil))]
    (reset! *state state')
    (go (-> (apply fetch-resources-with-method (if (map? state) 
                                                 state'
                                                 state)
                   method 
                   (if (= :dont-reset-preview-issue (last args))
                     (drop-last args)
                     args))
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))

(def ^:private aggregated-contexts-sequence (atom 0))

(defn fetch-and-reset-with-method-2!
  [*state state method & args]
  (apply fetch-and-reset-with-method! *state state method args)
  (let [sequence-number (swap! aggregated-contexts-sequence inc)]
    (js/setTimeout (fn []
                     (when (and (:selected-context @*state)
                                (= sequence-number @aggregated-contexts-sequence))
                       (go (-> (api/fetch-aggregated-contexts @*state)
                               <p!
                               (#(do 
                                   (prn "%" % "seq:" sequence-number "current:" @aggregated-contexts-sequence)
                                   (when (and % 
                                              (= sequence-number @aggregated-contexts-sequence))
                                     (swap! *state assoc :aggregated-contexts (second %)))))))))
                   200)))
