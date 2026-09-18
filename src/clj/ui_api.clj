(ns ui-api
  "The `/ui` surface, over the db handle it is given -- symmetric with
   `rest-api`, and extracted from `server` for the same reason that one grew a
   second arity: the hub answers these same commands off its own local
   DataSource (arch rework 2, step 2), and one definition answering in two
   processes beats two definitions agreeing to match.

   What is NOT here is the browser gate. `server` wraps this in the
   `:private-addr` / `:private-user-agent` check, because that guards the
   surface a browser can reach. The hub binds loopback and refuses anything
   else (`db-server/loopback`), so the gate would have nothing to add there --
   and putting it here would make the hub read config keys that describe
   somebody else's machine."
  (:require [compojure.core :refer [context POST]]
            [cambium.core :as log]
            [config :as config]
            [dispatch :as dispatch]
            [ring.middleware.json :as json]
            [ring.util.response :as response]))

(defn handler
  "The `/ui` POST handler: JSON envelope in, JSON envelope out, with the db
   handle `db-fn` returns injected as the dispatcher's server-arg.

   `db-fn` is a function, not a handle, for the reason `rest-api/rest-routes`
   spells out: this is built once and answers many requests, while the tests
   rebind `config/config` per test. Read it at call time or read the wrong one.

   `:refuse-command?` is an optional predicate on the command name, checked
   after the envelope is parsed and before the dispatcher sees it. The hub
   passes `placement/machine-local-command?` through it: a command that does
   its work on the human's own disk must not be answered by the machine that
   merely holds the database, because there the file work would silently
   succeed against the *wrong disk*. Refusing is the only outcome that can be
   noticed. It answers in the dispatcher's own envelope, as `:thrown`, so
   whoever misrouted the call gets it as an error rather than as data."
  ([db-fn] (handler db-fn nil))
  ([db-fn {:keys [refuse-command?]}]
   (-> (fn [req]
         (let [fn-name (get-in req [:body :fn])]
           (if (and refuse-command? (refuse-command? fn-name))
             (do (log/warn {:event "machine-local-refusal" :uri "/ui" :fn fn-name}
                           (str "refused /ui command " fn-name
                                ": it belongs on the machine with the browser"))
                 (response/response
                   {:return nil
                    :thrown (str "this server does not answer " fn-name
                                 ": it is a machine-local command and has to run "
                                 "where the files are")}))
             (response/response
               (log/with-logging-context
                 {:context :request}
                 (dispatch/handler (assoc-in req [:body :server-args :db] (db-fn))))))))
       json/wrap-json-response
       (json/wrap-json-body {:keywords? true}))))

(defn ui-routes
  "`/ui` mounted as a route. The 0-arity reads the global handle, as `server`
   has always done; the 1-arity is the hub's."
  ([] (ui-routes #(:db config/config) nil))
  ([db-fn] (ui-routes db-fn nil))
  ([db-fn opts]
   (let [h (handler db-fn opts)]
     (context "/ui" [] (POST "/" req (h req))))))
