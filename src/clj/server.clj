(ns server
  (:require [ring.adapter.jetty :as j]
            upload
            [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [config :as config]
            [dev-seed :as dev-seed]
            [datastore.schema :as schema]
            [next.jdbc :as jdbc]
            [repository :as r]
            [et.vp.ds :as datastore]
            opener
            dispatch
            rest-api
            [cambium.core :as log]
            [ring.middleware.resource :refer [wrap-resource]]
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

;; In prod the directories configured under :folders are served beneath the
;; /imgs URL prefix (both validated to exist at config load time), so no
;; symlinks are needed: :images backs /imgs/* (tracked originals) and
;; :preview-images backs /imgs/Preview/* (generated previews). In dev /imgs is
;; served from the classpath via wrap-resource, so these are unused there.
(def ^:private images-folder
  (-> config/config :folders :images))

(def ^:private preview-images-folder
  (-> config/config :folders :preview-images))

(defn- wrap-imgs
  "Serve files under the /imgs/* URL prefix from the filesystem: /imgs/Preview/*
  from preview-images-folder, everything else under /imgs/* from images-folder.
  file-response's :root guards against directory traversal."
  [handler images-folder preview-images-folder]
  (fn [req]
    (let [uri (:uri req)]
      (cond
        (str/starts-with? uri "/imgs/Preview/")
        (or (response/file-response (subs uri (count "/imgs/Preview")) {:root preview-images-folder})
            {:status 404 :body "Not Found"})

        (str/starts-with? uri "/imgs/")
        (or (response/file-response (subs uri (count "/imgs")) {:root images-folder})
            {:status 404 :body "Not Found"})

        :else (handler req)))))

(defn- img-by-id-handler
  [{{:keys [item-id]} :route-params}]
  (try (let [item (datastore/get-item (:db config/config) {:id item-id})
             data (:data item)
             title (:title item)
             resource-links (:resource-links data)]
         (cond (:image resource-links)
                 (let [file (io/file images-folder (:image resource-links))]
                   (if (.exists file)
                     (response/file-response (str file))
                     {:status 404 :body "Image file not found"}))
               (and title (re-matches #".*\.(png|jpg|jpeg|PNG|JPG|JPEG)$" title))
                 (let [file (io/file images-folder title)]
                   (if (.exists file)
                     (response/file-response (str file))
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
                        (wrap-imgs images-folder preview-images-folder)))]
    (-> (routes)
        wrap-env-defaults
        pipeline
        wrap-params
        wrap-multipart-params)))

(defn start-http-server!
  []
  (when (and (not (:dev? config/config))
             (or (nil? (:private-addr config/config))
                 (not (string? (:private-addr config/config)))))
    (throw (Exception. "config invalid")))
  (schema/apply-schema! (:db config/config))
  (dev-seed/maybe-seed! {:db         (:db config/config)
                         :dev?       (:dev? config/config)
                         :e2e?       (:e2e? config/config)
                         :skip-seed? (:skip-seed? config/config)})
  (let [host (or (:bind-host config/config)
                 (if (:dev? config/config) "0.0.0.0" "127.0.0.1"))]
    (future (j/run-jetty (app) {:port (:port config/config)
                                :host host}))))

(defn -main
  [& _args]
  (deref (start-http-server!)))