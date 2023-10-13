(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset!]]))

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
  (prn "update-issue!" issue)
  (fetch-and-reset! *state 
                    (-> @*state
                        (assoc :cmd :update-issue)
                        (assoc :arg {:issue issue 
                                     :issue-contexts issue-contexts})
                        (dissoc :modal))))

(defn update-context! [*state context]
  (fetch-and-reset! *state
                    (-> @*state
                        (assoc :cmd :update-context)
                        (assoc :arg context)
                        (dissoc :modal))))
