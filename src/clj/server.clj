(ns server
  (:require [ring.adapter.jetty :as j]
            upload
            [compojure.core :refer [context GET POST]]
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
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]])
  (:gen-class))

(defn api-handler [{{msg :msg} :body}]
  (tap> [:resources (r/list-resources)])
  {:body {:echo msg}})

(defn- open [{{:keys [file-id]} :route-params  
              :as _req}]
  (opener/open file-id)
  {:status 200})

(defn- api []
  (fn [req]
    (if
     (and
      (not (:dev? config/config))
      (or (not (= (:private-addr config/config) (:remote-addr req)))
          (not (= (:private-user-agent config/config) (get-in req [:headers "user-agent"])))))
      (do
        (log/warn (pr-str req))
        {:status 403})
      ((context "" []
         (->
          #(response/response 
            (log/with-logging-context {:context :request}
              (dispatch/handler (-> % 
                                    (assoc-in
                                     [:body :server-args :db]
                                     (:db config/config))))))
          json/wrap-json-response
          (json/wrap-json-body {:keywords? true})))
       req))))

(defn upload-handler [request]
  (let [uploaded-file (get (-> request :multipart-params) "file")
        id (get (-> request :multipart-params) "id")
        alternative-behaviour? (get (-> request :multipart-params) "alternative-behaviour")]
    (upload/upload-preview-file (:db config/config) uploaded-file id alternative-behaviour?)
    ;; Process the uploaded file here. For example, save it to a directory.
    (response/response "File uploaded successfully!")))

(defn- routes []
  (context "/" []
    (context "/api" []
      (POST "/" [] (api)))
    (GET "/open/:file-id" [] open)
    (POST "/upload" req (upload-handler req))
    (GET "/" [] (response/resource-response "public/index.html"))
    (fn [req] 
      (log/warn (str "File not found:" (:uri req)))
      {:status 404 :body "Not Found"})))

(def dev? (true? (-> (read-string (slurp "./config.edn")) :dev?)))

(defn app []
  (let [pipeline (if dev? 
                   #(-> %
                       (wrap-resource "public" {:allow-symlinks? true}))
                   #(-> % 
                        (wrap-resource "public")
                        (wrap-file "./public" {:allow-symlinks? true})))] 
    (-> (routes) 
        wrap-env-defaults
        pipeline
        wrap-multipart-params)))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (do
    (prn "config valid??" config/config)
    (when (and (not (:dev? config/config)) 
               (or (nil? (:private-addr config/config))
                   (not (string? (:private-addr config/config)))))
      (throw (Exception. "config invalid")))
    (future (j/run-jetty (app) {:port (:port config/config)})))
  :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))