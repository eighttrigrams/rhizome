(ns et.rz.hub.ui-api
  "The `/ui` surface, over the db handle it is given -- symmetric with
   `et.rz.hub.rest-api`, and extracted from `server` for the same reason that one grew a
   second arity: the hub answers these same commands off its own local
   DataSource (arch rework 2, step 2), and one definition answering in two
   processes beats two definitions agreeing to match.

   What is NOT here is the browser gate. `server` wraps this in the
   `:private-addr` / `:private-user-agent` check, because that guards the
   surface a browser can reach. The hub binds loopback and refuses anything
   else (`et.rz.hub.main/loopback`), so the gate would have nothing to add there --
   and putting it here would make the hub read config keys that describe
   somebody else's machine."
  (:require [compojure.core :refer [context POST]]
            [cambium.core :as log]
            [et.rz.config :as config]
            [et.rz.hub.dispatch :as dispatch]
            [ring.middleware.json :as json]
            [ring.util.response :as response]))

(defn handler
  "The `/ui` POST handler: JSON envelope in, JSON envelope out, with the db
   handle `db-fn` returns injected as the dispatcher's server-arg.

   `db-fn` is a function, not a handle, for the reason `et.rz.hub.rest-api/rest-routes`
   spells out: this is built once and answers many requests, while the tests
   rebind `config/config` per test. Read it at call time or read the wrong one.

   `:intercept` is an optional `(fn [fn-name req])` consulted once the envelope
   is parsed and before the dispatcher sees it. Answer a ring response to take
   the call over; answer nil to let it through. Both halves of the split use
   it, for opposite purposes and from the same classification:

   - the **hub** intercepts machine-local commands and refuses them. It must
     refuse rather than let them through, because they would otherwise
     *succeed*: the file work would run against the wrong machine's disk with
     nothing to show for it and nothing to report.
   - the **`server`** intercepts everything that is not machine-local and
     forwards it, keeping only the commands that need this disk.

   One hook, because there is one classification. A second mechanism here
   would be a second place for the two halves to disagree."
  ([db-fn] (handler db-fn nil))
  ([db-fn {:keys [intercept]}]
   (-> (fn [req]
         (let [fn-name (get-in req [:body :fn])]
           (or (when intercept (intercept fn-name req))
               (response/response
                 (log/with-logging-context
                   {:context :request}
                   (dispatch/handler (assoc-in req [:body :server-args :db] (db-fn))))))))
       json/wrap-json-response
       (json/wrap-json-body {:keywords? true}))))

(defn refusal
  "A refusal in the dispatcher's own envelope, so that whoever misrouted the
   call reads it as an error rather than as data."
  [fn-name event reason]
  (log/warn {:event event :uri "/ui" :fn fn-name}
            (str "refused /ui command " fn-name ": " reason))
  (response/response {:return nil
                      :thrown (str "this server does not answer " fn-name ": " reason)}))

(defn ui-routes
  "`/ui` mounted as a route. The 0-arity reads the global handle, as `server`
   has always done; the 1-arity is the hub's."
  ([] (ui-routes #(:db config/config) nil))
  ([db-fn] (ui-routes db-fn nil))
  ([db-fn opts]
   (let [h (handler db-fn opts)]
     (context "/ui" [] (POST "/" req (h req))))))
