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
            opener
            dispatch
            [cambium.core :as log]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]))

(defn api-handler [{{msg :msg} :body}]
  (tap> [:resources (r/list-resources)])
  {:body {:echo msg}})

(defn- open [{{:keys [file-id]} :route-params  
              :as _req}]
  (opener/open file-id)
  {:status 200})

(defn- api [mode]
  (fn [req]
    (if (or (and (= :public mode)
                 #_true
                 (or (not @privacy/*public?)
                     (and (not (:dev? config/config))
                          (or (not (= (:public-addr config/config) (:remote-addr req)))
                              (not (= (:public-user-agent config/config) (get-in req [:headers "user-agent"])))))))
            (and (= :private mode)
                 (not (:dev? config/config))
                 (or (not (= (:private-addr config/config) (:remote-addr req)))
                     (not (= (:private-user-agent config/config) (get-in req [:headers "user-agent"]))))))
      (do
        (log/warn (pr-str req))
        {:status 403})
      ((context "" []
         (->
          #(response/response (dispatch/handler (-> % 
                                                    (assoc-in
                                                     [:body :server-args :db]
                                                     (:db config/config))
                                                    (assoc-in
                                                     [:body :server-args :privacy-mode]
                                                     mode))))
          json/wrap-json-response
          (json/wrap-json-body {:keywords? true})))
       req))))

(defn- routes [mode]
  (fn [req]
    (
     (context "/" []
       (context "/api" []
         (POST "/" [] (api mode)))
       (GET "/open/:file-id" [] open)
       (GET "/" [] (response/resource-response "public/index.html")))
     req))) ;; TODO use route/resources (see cljsc-webstacks)

(defn app [mode]
  (fn [req]
    ((-> (routes mode)
          wrap-env-defaults
          (wrap-resource "public")
          (wrap-file "./public" {:allow-symlinks? true}))
     req)))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (do
    (prn "config valid?" config/config)
    (when (and (not (:dev? config/config)) 
               (or (nil? (:private-addr config/config))
                   (not (string? (:private-addr config/config)))))
      (throw (Exception. "config invalid")))
    (let [port (:port config/config)]
        (future (j/run-jetty (app :private) (if (:dev? config/config)
                                              {:port port}
                                              {:ssl?     true
                                               :http?    false
                                               :keystore "keystore.jks"
                                               :key-password (:key-password config/config)
                                               :ssl-port port})))) 
    (let [port (+ (:port config/config) 2)]
        (future (j/run-jetty (app :public) (if (:dev? config/config)
                                             {:port port}
                                             {:ssl?     true
                                              :http?    false
                                              :keystore "keystore.jks"
                                              :key-password (:key-password config/config)
                                              :ssl-port port})))))
  :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))