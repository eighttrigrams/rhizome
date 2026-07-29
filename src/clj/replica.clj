(ns replica
  "Graceful refusals for read-only replica mode.

   Whether this process is a replica was decided once, at startup, by
   `config/read-only-replica?` (prod mode and no `primary.nosync` marker in the
   start directory) -- this namespace only reads that decision and turns it into
   refusals. They sit in FRONT of the structural guarantee: a replica's
   datasource is opened read-only, so a write that slipped past them would come
   back as a raw SQLException, and no request should ever surface one.

   Who refuses what:
   - `/api`         rest-api.middleware/wrap-refuse-writes -- every mutating method
   - `/ui`          dispatch/handler -- mutating commands only, queries pass
   - `/upload`      server/upload-handler
   - the pollers    server/poll-scheduling-enabled? -- not scheduled at all
   - the UI banner  ui.replica, over GET /api/status"
  (:require [cheshire.core :as json]
            [config :as config]))

(defn read-only?
  "True when this process booted as a read-only replica. Constant for the
   process lifetime -- promoting a replica means placing the marker and
   restarting (see config/read-only-replica?)."
  []
  (boolean (:read-only-replica? config/config)))

(def message
  "The one sentence every refusal carries: HTTP bodies, the /ui dispatch
   refusal, the UI banner's tooltip."
  (str "This instance is a read-only replica: it booted in prod mode without a "
       config/primary-marker " marker in its start directory, so nothing can be "
       "written. To promote it, place " config/primary-marker " next to config.edn "
       "and restart."))

(defn refusal-response
  "Ring response for a refused HTTP write: 403 with a JSON body, following the
   recording-mode drop precedent (documented as `403 {\"dropped\":true,…}` in
   /api/describe and the rhizome-user skill) and the other prod-mode refusals in
   `server`."
  []
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error message :read-only-replica true})})
