(ns ui.actions.common
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            api
            [ui.replica :as replica]
            utils))

(defn save-input! [*state] (swap! *state assoc :loading false))

(def save-input-debounced! (utils/debounce save-input! 500))

(defn reset-state!
  [new-state *state]
  (when new-state ;; defensive: nothing to install when there's no new state
    ;; Preserve modal state when resetting, but only if new-state doesn't explicitly close it
    (let [current-modal (:modal @*state)
          new-state-has-modal? (contains? new-state :modal)
          ;; Whether there is still a modal once this response has landed. The
          ;; relation modal's two keys are carried across a save -- a refused one
          ;; has to come back to what the user was typing in -- and they are the
          ;; open modal's, not the app's: kept past the save that closed it they
          ;; would ride along on every later request, since the whole state is
          ;; what a request is made of.
          modal-after? (if new-state-has-modal? (:modal new-state) current-modal)
          current-annotation-edit-item (when modal-after? (:annotation-edit-item @*state))
          ;; Kept with the item and not recomputed: it is the whole the open
          ;; modal is editing the annotation of, settled from the row that was
          ;; clicked. Losing it here would put the modal back on the selected
          ;; context, which below level 1 is a different edge.
          current-annotation-edit-context (when modal-after? (:annotation-edit-context @*state))
          ;; What the floating player is playing (ui.floating-player). Taken
          ;; from the live atom and put back unconditionally, rather than
          ;; preserved only when set: `new-state` is a snapshot from when the
          ;; request went out, so it has an opinion about the player that is as
          ;; old as the request. A response landing after the X was pressed
          ;; would otherwise restart the video the owner just closed, and one
          ;; landing after a poster was clicked would take away the video he
          ;; just started. The player goes away in two ways and a stale
          ;; snapshot is neither of them.
          current-playing-video (:playing-video @*state)]
      (reset! *state (-> new-state
                         (dissoc :enter-pressed? :playing-video)
                         (cond-> (and current-modal (not new-state-has-modal?))
                                   (assoc :modal current-modal)
                                 current-annotation-edit-item
                                   (assoc :annotation-edit-item current-annotation-edit-item)
                                 current-annotation-edit-context
                                   (assoc :annotation-edit-context
                                          current-annotation-edit-context)
                                 current-playing-video
                                   (assoc :playing-video current-playing-video)))))))

(defn- update-state
  [response state]
  ;; A read-only replica answers a refused write in the normal envelope, so the
  ;; notice is taken off here -- once, for every command that flows through.
  (let [{:keys [items contexts aggregated-contexts] :as i} (replica/refusal-notice! response)]
    (merge (if (map? state) state @state)
           i
           {:items (if items items (:items state))
            :contexts (or contexts (:contexts state))}
           (when aggregated-contexts {:aggregated-contexts aggregated-contexts}))))

(defn- list-resources [state] (api/list-resources (dissoc state :items :contexts)))

(defn- fetch-resources
  [state]
  (go (-> state
          list-resources
          <p!
          (update-state state))))

(defn- dissoc-loading [_ *state] (save-input-debounced! *state))

(defn fetch-and-reset!
  [*state state]
  ;; :preview-relation goes out with :preview-item, and for a sharper version of
  ;; its reason: the whole state is what a request is made of, and this one holds
  ;; a body of text fetched for one hover. Left in, the text the feature goes to
  ;; some length not to load with a list would be uploaded with every request
  ;; after it.
  (let [state (assoc state
                :loading true
                :preview-item nil
                :preview-relation nil)]
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
  (let [state''' (if (map? state) state @state)
        state' (assoc state''' :loading true :preview-item nil :preview-relation nil)]
    (reset! *state state')
    (go (-> (apply fetch-resources-with-method
                   (if (map? state) state' state)
                   method
                   args)
            <!
            (reset-state! *state)
            (dissoc-loading *state)))))







;; -------------------------
;; all of this is for fetch-item or fetch-last context, where aggregated-items are fetched
;; separately
;; for quicker response times

(defn- update-state-2
  [new-state *state]
  (reset! *state (merge @*state (replica/refusal-notice! new-state))))

(defn- fetch-resources-with-method-2
  [*state method & args]
  (go (-> (apply method @*state args)
          <p!
          (update-state-2 *state))))

(defn fetch-and-reset-with-method-2!
  [*state method & args]
  (go (-> (apply fetch-resources-with-method-2 *state method args)
          <!
          ((fn [_]
             (when (:selected-item @*state)
               (go (-> (api/fetch-aggregated-contexts @*state)
                       <p!
                       (#(swap! *state assoc :aggregated-contexts %))))))))))
