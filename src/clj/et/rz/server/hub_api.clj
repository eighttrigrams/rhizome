(ns et.rz.server.hub-api
  "Reading the hub's `/api` from the `server`, for the two places where a
   machine-local route needs a fact about an item before it can do its own work.

   This is the first time the outer half *calls* the inner rather than
   forwarding to it, so it is a namespace with one job rather than a helper
   hidden inside the route that wanted it first (arch rework 2, step 4).

   ## Why the data crosses and the bytes do not

   `/img-by-id` and `/imgs/*` serve image files to the browser. Those files are
   in iCloud-synced folders, so every machine already has them -- which means
   the bytes have no reason to cross a tunnel to reach a browser two feet from
   the file. What does have to cross is the small question *which file*, because
   only the hub holds the row that answers it.

   ## One code path, both modes

   With a hub, these are HTTP calls. Without one -- test mode, e2e, a dev
   session with no hub -- the *same request map* is handed to
   `et.rz.hub.rest-api/rest-routes` in this process, over the local handle. Not a second
   implementation reading the database directly: the same routes, the same
   response shape, the same JSON, so there is one answer to be wrong about
   rather than two that have to agree."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [et.rz.config :as config]
            [et.rz.server.hub-proxy :as hub-proxy]
            [et.rz.hub.rest-api :as et.rz.hub.rest-api]))

(def ^:private local-routes
  "The same routes the hub mounts, over this process's own handle. Built once,
   and only ever reached when there is no hub -- see the namespace docstring."
  (delay (et.rz.hub.rest-api/rest-routes #(:db config/config))))

(def ^:private request-defaults
  "`hub-proxy`'s, with a tighter ceiling on silence.

   Ten seconds instead of sixty, because what this namespace asks for is one
   indexed row -- which file does this item name -- and nothing on the other
   side of these two calls scrapes, embeds or sweeps. The generous bound
   `hub-proxy/forward` carries is headroom for hub-side work that genuinely
   takes that long; there is none here to leave room for.

   And the cost of being generous is paid per image. `/img-by-id` is one call
   per picture on the page, each holding the jetty thread that is serving it,
   so a page of twenty against a tunnel that accepts and never answers is the
   thread pool. That is the failure this number is chosen against."
  (assoc hub-proxy/request-defaults :socket-timeout 10000))

(defn- get-json
  "GET an `/api` path and answer `[status parsed-body]`.

   A hub that cannot be reached is `[502 nil]` rather than a throw: every caller
   here is a route serving a browser, and 'the tunnel is down' has to read as a
   missing image rather than a 500 with a stack trace.

   That promise needs `request-defaults` above to keep it. It was made without
   them and was false in the case it was written for: a *closed* port answers
   connection-refused and comes back 502 at once, but a half-dead tunnel accepts
   and stays silent, and this call had nothing to give up on. It hung, one
   jetty thread per image, which is not a missing image -- it is a page that
   never finishes loading and a `server` that stops answering for anything
   else, including the local files it alone can serve."
  [path]
  (try
    (if-let [url (hub-proxy/hub-url)]
      (let [resp (http/get (str url path)
                           (merge request-defaults
                                  {:as :string :throw-exceptions false}))]
        [(:status resp) (json/parse-string (:body resp) true)])
      (let [resp (@local-routes {:request-method :get :uri path :headers {}})
            body (:body resp)]
        [(:status resp)
         (json/parse-string (if (bytes? body) (String. ^bytes body "UTF-8") (str body))
                            true)]))
    (catch Exception _ [502 nil])))

(defn item-images
  "The manifest from `GET /api/items/:id/images`: what images the item declares,
   `:images` for the ones the hub could resolve and `:missing` for the ones it
   could not. Nil when there is no such item.

   Both lists matter to a caller on another machine. `:missing` means the *hub*
   could not find the file, which on a machine whose sync has delivered it is
   not the same answer -- so the filename is what is wanted from either list,
   and whether it is there is this machine's own question to ask of its disk."
  [id]
  (let [[status body] (get-json (str "/api/items/" id "/images"))]
    (when (= 200 status) body)))

(defn item
  "`GET /api/items/:id`, or nil. Note the endpoint answers 200 with an empty
   shell for an id that does not exist, so `:id` is what says whether there is
   an item -- that is the REST surface's own documented quirk, not this
   namespace's."
  [id]
  (let [[status body] (get-json (str "/api/items/" id))]
    (when (and (= 200 status) (:id body)) body)))
