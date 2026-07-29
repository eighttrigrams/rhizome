(ns ui.replica
  "The UI half of read-only replica mode: a standing banner for as long as the
   mode holds, and feedback whenever a write is refused.

   The role is learned over the channel the ⚠ REC badge already uses for server
   state -- a fetch against the REST API whose JSON lands in an atom -- but it is
   deliberately kept OUT of the app's *state atom: every dispatch response
   reset!s that atom from a snapshot taken when the request went out, so a flag
   that arrives mid-flight can be dropped again. The banner must not blink out.

   Nothing here can turn the mode off: the server decided it once at startup
   (see config/read-only-replica?), and this only asks."
  (:require [reagent.core :as r]))

(defonce ^:private *read-only? (r/atom false))

(defn load!
  "Ask the server which kind of instance this is, once, at mount. Dev instances
   always answer false, so nothing shows up there.

   A failed probe leaves the banner off -- it cannot be helped from here, the
   role is the server's to report -- but it must not fail silently: without the
   log line the UI's only standing signal of the mode would just be missing, with
   nothing to diagnose it by."
  []
  (-> (js/fetch "/api/status")
      (.then (fn [^js resp] (.json resp)))
      (.then (fn [^js data]
               (reset! *read-only?
                       (boolean (:read-only-replica (js->clj data :keywordize-keys true))))))
      (.catch (fn [err]
                (js/console.error
                  "Could not read /api/status — the read-only replica banner stays off:"
                  err)))))

(defn refused-write-message
  "The refusal sentence out of a raw JSON error body, or nil when the failure was
   something else. The HTTP writes (/upload, /api) answer a refusal as
   403 {\"read-only-replica\":true,\"error\":…} -- see replica/refusal-response."
  [body]
  (try (let [{:keys [read-only-replica error]}
               (js->clj (js/JSON.parse body) :keywordize-keys true)]
         (when read-only-replica error))
       (catch :default _ nil)))

(defn refusal-notice!
  "Report a refused write and hand back the response without the refusal key, so
   it does not travel on in app state. Alerting is how a dropped write is already
   reported in this UI (see ui.danger-mode)."
  [response]
  (if-let [msg (and (map? response) (:read-only-refused response))]
    (do (js/window.alert msg)
        (dissoc response :read-only-refused))
    response))

(defn banner
  "Standing, non-dismissible marker that this instance takes no writes. Same
   visual family as the ⚠ REC badge, with a red glow, and centred so the two can
   never sit on top of each other."
  []
  (when @*read-only?
    [:div#read-only-indicator
     {:title "Read-only replica — this copy takes no writes. The machine holding primary.nosync does."
      :style {:position "fixed"
              :top "6px"
              :left "50%"
              :transform "translateX(-50%)"
              :z-index 10000
              :background "#c0392b"
              :color "white"
              :padding "4px 8px"
              :border-radius "4px"
              :font-size "12px"
              :font-weight "bold"
              :box-shadow "0 0 10px 3px rgba(231,76,60,0.9), 0 1px 3px rgba(0,0,0,0.3)"
              :pointer-events "none"
              :user-select "none"}}
     "⚠ READ-ONLY REPLICA"]))
