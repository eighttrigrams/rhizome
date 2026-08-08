(ns ui.modals.actions
  (:require api
            [ui.actions.common :refer [fetch-and-reset! fetch-and-reset-with-method!]]))

(defn cancel-modal!
  [*state]
  (reset! *state (-> @*state
                     (dissoc :modal :annotation-edit-item :show-confirm-discard :part-of-refused :save-failed)
                     (assoc :loading true)))
  (js/setTimeout (fn [_] (swap! *state dissoc :loading)) 500))

(defn save-description-and-leave-open!
  [*state item]
  (fetch-and-reset! *state
                    (-> @*state
                        #_(dissoc :modal)
                        (assoc :cmd :update-context-description)
                        (assoc :arg item))))

(defn update-context!
  [*state context item-contexts]
  ;; The modal stays open across the save, and the server closes it: the response
  ;; carries :modal nil when the save went through and :modal :edit-context when
  ;; it was refused. Taking :modal out here instead would unmount the modal the
  ;; moment the request goes out, and a refusal would remount it -- rebuilding
  ;; every uncontrolled input in it from :defaultValue, which is to say throwing
  ;; away the title, the tags, the sibling index and everything else the user had
  ;; typed. None of it was saved either, since a refused save writes nothing.
  ;;
  ;; :part-of-refused does go out with the save, so a save that succeeds clears
  ;; the refusal the previous one left standing. A save refused again gets it
  ;; back from the response.
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :part-of-refused :save-failed)
                                api/update-item
                                {:context context :item-contexts item-contexts}))

(defn discard-obsidian-and-close!
  [*state]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal :show-confirm-discard)
                                api/discard-obsidian-changes))

(defn sync-obsidian-and-close!
  [*state item]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal :show-confirm-discard)
                                api/sync-obsidian-changes
                                item))

(defn save-annotations-and-close!
  [*state annotations-data]
  (fetch-and-reset-with-method! *state
                                (dissoc @*state :modal :annotation-edit-item)
                                api/update-annotations
                                annotations-data))
