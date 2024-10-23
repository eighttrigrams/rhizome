(ns ui.modals.key-handler
  (:require [ui.modals.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*]]))

(defn handle-modal-keys [*state value-fn]
  (handle-keys*
   (fn [code _ctrl-pressed? meta-pressed? alt-pressed? _shift-pressed? e]
     (let [{:keys [modal]} @*state]
       (cond (= "Escape" code)
             (actions/cancel-modal! *state)
             (and (= "Digit9" code) 
                  (or meta-pressed? alt-pressed?)
                  (= :description modal))
             (do (.preventDefault e)
                 (actions/save-description! *state (value-fn))))))))

(defn handle-edit-keys [*state value-fn value-fn-2]
  (handle-keys*
   (fn [code _ctrl-pressed? meta-pressed? alt-pressed? _shift-pressed? e]
     (cond (= "Escape" code)
           (actions/cancel-modal! *state)
           (and (= "Digit9" code)
                (or meta-pressed? alt-pressed?))
           (do (.preventDefault e)
               (actions/update-context! *state (value-fn) (value-fn-2)))))))
