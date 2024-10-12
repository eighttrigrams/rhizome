(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset!
                                       fetch-and-reset-with-method!]]))

(defn cancel-modal! [*state]
  (reset! *state (-> @*state
                     (dissoc :modal)
                     (assoc :loading true)))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn save-description! [*state type item]
  (fetch-and-reset! *state (-> @*state
                               (dissoc :modal)
                               (assoc :cmd (if (= :issue type)
                                             :update-issue-description
                                             :update-context-description))
                               (assoc :arg item))))

(defn update-issue! [*state issue issue-contexts]
  (fetch-and-reset-with-method! *state 
                               (dissoc @*state :modal)
                               api/update-issue
                               {:issue          issue 
                                :issue-contexts (map (fn [issue-context] 
                                                       {:id issue-context :show-badges? true}) issue-contexts)}))

(defn update-context! [*state context]
  (fetch-and-reset-with-method! *state 
                               (dissoc @*state :modal)
                               api/update-context
                               {:context        context}))
