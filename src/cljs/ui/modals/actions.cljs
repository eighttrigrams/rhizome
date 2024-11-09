(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset!
                                       fetch-and-reset-with-method!]]))

(defn cancel-modal! [*state]
  (reset! *state (-> @*state
                     (dissoc :modal)
                     (assoc :loading true)))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn save-description-and-leave-open! [*state item]
  (fetch-and-reset! *state (-> @*state
                               #_(dissoc :modal)
                               (assoc :cmd :update-context-description)
                               (assoc :arg item))))

(defn update-context! [*state context issue-contexts]
  (fetch-and-reset-with-method! *state 
                               (dissoc @*state :modal)
                               api/update-item
                               {:context        context
                                :issue-contexts issue-contexts}))
