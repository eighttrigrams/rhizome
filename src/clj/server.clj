(ns server
  (:require [ring.adapter.jetty :as j]
            upload
            [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [mount.core :as mount]
            [datastore.config :as config]
            [datastore.schema :as schema]
            [next.jdbc :as jdbc]
            [repository :as r]
            [et.vp.ds :as datastore]
            opener
            dispatch
            rest-api
            [cambium.core :as log]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.io :as io])
  (:gen-class))

(defn api-handler [{{msg :msg} :body}] (tap> [:resources (r/list-resources)]) {:body {:echo msg}})

(defn- open [{{:keys [file-id]} :route-params}] (opener/open file-id) {:status 200})

(defn- api
  []
  (fn [req]
    (if (and (not (:dev? config/config))
             (or (not (= (:private-addr config/config) (:remote-addr req)))
                 (not (= (:private-user-agent config/config)
                         (get-in req [:headers "user-agent"])))))
      (do (log/warn (pr-str req)) {:status 403})
      ((context ""
                []
                (-> #(response/response (log/with-logging-context
                                          {:context :request}
                                          (dispatch/handler (-> %
                                                                (assoc-in [:body :server-args :db]
                                                                          (:db config/config))))))
                    json/wrap-json-response
                    (json/wrap-json-body {:keywords? true})))
        req))))

(defn upload-handler
  [request]
  (let [uploaded-file (get (-> request
                               :multipart-params)
                           "file")
        id (get (-> request
                    :multipart-params)
                "id")
        alternative-behaviour? (get (-> request
                                        :multipart-params)
                                    "alternative-behaviour")]
    (upload/upload-preview-file (:db config/config) uploaded-file id alternative-behaviour?)
    ;; Process the uploaded file here. For example, save it to a directory.
    (response/response "File uploaded successfully!")))

(def homefolder
  (-> (config/ds)
      :folders
      :homefolder))

(defn- img-by-id-handler
  [{{:keys [item-id]} :route-params}]
  (try (let [item (datastore/get-item (:db config/config) {:id item-id})
             data (:data item)
             title (:title item)
             resource-links (:resource-links data)]
         (cond (:image resource-links)
                 (let [path (str homefolder "Pictures/Tracked/" (:image resource-links))
                       file (io/file path)]
                   (if (.exists file)
                     (response/file-response path)
                     {:status 404 :body "Image file not found"}))
               (and title (re-matches #".*\.(png|jpg|jpeg|PNG|JPG|JPEG)$" title))
                 (let [path (str homefolder "Pictures/Tracked/" title)
                       file (io/file path)]
                   (if (.exists file)
                     (response/file-response path)
                     {:status 404 :body "Image file not found"}))
               :else {:status 404 :body "Item has no image"}))
       (catch Exception e
         (log/error e "Error serving image by ID")
         {:status 500 :body "Internal server error"})))

(defn- reset-handler
  [_req]
  (if (true? (:dev? config/config))
    (let [db (:db config/config)]
      (jdbc/execute-one! db ["DELETE FROM relations"])
      (jdbc/execute-one! db ["DELETE FROM items"])
      {:status 200 :body "ok"})
    {:status 403 :body "not in dev mode"}))

(defn- routes
  []
  (context
    "/"
    []
    (context "/api" [] (POST "/" [] (api)))
    (rest-api/rest-routes)
    (POST "/test/reset" [] reset-handler)
    (GET "/open/:file-id" [] open)
    (GET "/img-by-id/:item-id" [] img-by-id-handler)
    (POST "/upload" req (upload-handler req))
    (GET "/" [] (response/resource-response "public/index.html"))
    (fn [req] (log/warn (str "File not found:" (:uri req))) {:status 404 :body "Not Found"})))

(def dev?
  (true? (-> (config/ds)
             :dev?)))

(defn app
  []
  (let [pipeline (if dev?
                   #(-> %
                        (wrap-resource "public" {:allow-symlinks? true}))
                   #(-> %
                        (wrap-resource "public")
                        (wrap-file "./public" {:allow-symlinks? true})))]
    (-> (routes)
        wrap-env-defaults
        pipeline
        wrap-params
        wrap-multipart-params)))

(mount/defstate ^{:on-reload :noop} http-server
                :start (do (prn "config valid??" config/config)
                           (when (and (not (:dev? config/config))
                                      (or (nil? (:private-addr config/config))
                                          (not (string? (:private-addr config/config)))))
                             (throw (Exception. "config invalid")))
                           (schema/apply-schema! (:db config/config))
                           (let [host (or (:bind-host config/config)
                                          (when (and (:dev? config/config)
                                                     (= "1" (System/getenv "RHIZOME_BIND_ALL")))
                                            "0.0.0.0")
                                          "127.0.0.1")]
                             (future (j/run-jetty (app) {:port (:port config/config)
                                                         :host host}))))
                :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))