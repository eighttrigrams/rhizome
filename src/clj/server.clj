(ns server
  (:require [ring.adapter.jetty :as j]
            privacy
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

(defn- api [mode]
  (fn [req]
    (prn "..." :mode @privacy/*public?)
    (if (and (= :private mode)
             (not @privacy/*public?))
      {:status 403}
      ((context "" []
         (->
          #(response/response (dispatch/handler (assoc-in %
                                                          [:body :server-args :db]
                                                          (:db config/config))))
          json/wrap-json-response
          (json/wrap-json-body {:keywords? true})))
       req))))

(defn- routes [mode]
  (fn [req]
    (
     (context "/" []
       (context "/api" []
         (POST "/" [] (api mode)))
       (GET "/" [] (response/resource-response "public/index.html")))
     req))) ;; TODO use route/resources (see cljsc-webstacks)

(defn app [mode]
  (fn [req]
    ((-> (routes mode)
          wrap-env-defaults
          (wrap-resource "public"))
     req)))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (do (future (j/run-jetty (app :public) {:port (:port config/config)})) 
      (future (j/run-jetty (app :private) {:port (+ (:port config/config) 2)})))
  :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))