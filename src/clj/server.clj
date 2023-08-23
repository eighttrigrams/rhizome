(ns server
  (:require [ring.adapter.jetty :as j]
            [compojure.core :refer [defroutes context GET POST]]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [mount.core :as mount]
            [datastore.config :as config]
            [repository :as r]
            dispatch
            [ring.middleware.resource :refer [wrap-resource]]))

(defn api-handler [{{msg :msg} :body}]
  (tap> [:resources (r/list-resources)])
  {:body {:echo msg}})

(defroutes api
  (-> 
   #(response/response (dispatch/handler (assoc-in % 
                                                   [:body :server-args :db]
                                                   (:db config/config))))
   json/wrap-json-response
   (json/wrap-json-body {:keywords? true})))

(defroutes routes
  (context "/api" []
    (POST "/" [] api))
  (GET "/" [] (response/resource-response "public/index.html"))) ;; TODO use route/resources (see cljsc-webstacks)

(def app
  (-> routes
      wrap-env-defaults
      (wrap-resource "public")))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (future (j/run-jetty app {:port (:port config/config)}))
  :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))