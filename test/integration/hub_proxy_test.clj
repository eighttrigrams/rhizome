(ns hub-proxy-test
  "`server` hands the item surfaces to the hub (arch rework 2, step 3).

   The test stands the pair up for real: a hub on an ephemeral port with its
   own file database, and `server`'s ring app built against a *remote* handle
   pointing at it. Nothing is stubbed, so what is asserted is the thing that
   has to be true from another machine -- a request arriving at this machine's
   `/api` is answered out of the database on the other one.

   The discriminator throughout is that the hub's database and the test-mode
   global database are different databases. A context written straight into the
   hub is invisible to the global handle, so an answer that contains it can
   only have come from the hub."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [config :as config]
            [db-server]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set]
            [placement :as placement]
            [server]))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-proxy-test" ".db")
                      (.deleteOnExit))))

(defn- with-pair
  "A live hub, and `server`'s app pointed at it by a remote handle."
  [f]
  (let [hub (db-server/start! {:port 0 :db-path (temp-db-path)})]
    (try
      ;; `:hub-url` is what makes this process a forwarding `server`; `:db` stays
      ;; as test mode set it, which is a DIFFERENT database from the hub's file
      ;; and is exactly what makes the assertions here mean something.
      (with-redefs [config/config (assoc config/config :hub-url (:url hub) :dev? true)]
        (f hub (server/app)))
      (finally (db-server/stop! hub)))))

(defn- GET* [app uri]
  (let [resp (app {:request-method :get :uri uri :headers {} :body nil})]
    [(:status resp)
     (let [b (:body resp)]
       (json/parse-string (if (bytes? b) (String. ^bytes b "UTF-8") (str b)) true))]))

(defn- seed-context!
  "Write a context straight into the hub, through its own /ui."
  [hub title]
  (let [os (java.io.ByteArrayOutputStream. 512)]
    (transit/write (transit/writer os :json) [nil {:title title}])
    (http/post (str (:url hub) "/ui")
                          {:body (json/generate-string {:fn "insert-context"
                                                        :args (.toString os "UTF-8")})
                           :content-type :json :accept :json :as :string})))

(deftest api-is-answered-by-the-hub-test
  (with-pair
    (fn [hub app]
      (seed-context! hub "OnTheHub")
      (testing "a GET to this machine's /api comes back with the hub's rows"
        (let [[status body] (GET* app "/api/contexts")]
          (is (= 200 status))
          (is (some #(= "OnTheHub" (:title %)) body)
              (str "the answer did not come from the hub. Got: " (pr-str body))))))))

(deftest the-describe-is-not-forwarded-test
  (with-pair
    (fn [_hub app]
      (testing "/api/describe is answered here, not by the hub"
        ;; The hub still answers that path with the statement protocol's
        ;; description (db-server registered it first, and prober reads it).
        ;; Forwarding it would hand agents the wrong document. When step 4
        ;; retires the protocol this test is what says the exception can go.
        (let [[status body] (GET* app "/api/describe")]
          (is (= 200 status))
          (is (not-any? #(= "execute" (:name %)) (:endpoints body))
              (str "the statement protocol's describe leaked through the proxy. "
                   "Got: " (pr-str (map :name (:endpoints body))))))))))

(defn- POST-ui*
  "POST a /ui envelope to a ring app, and answer the parsed envelope."
  [app fn-name args]
  (let [os (java.io.ByteArrayOutputStream. 512)
        _  (transit/write (transit/writer os :json) args)
        body (cheshire.core/generate-string {:fn fn-name :args (.toString os "UTF-8")})
        resp (app {:request-method :post
                   :uri            "/ui"
                   :headers        {"content-type" "application/json"
                                    "accept"       "application/json"}
                   :body           (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})
        b    (:body resp)]
    (assoc (json/parse-string (if (bytes? b) (String. ^bytes b "UTF-8") (str b)) true)
      :status (:status resp))))

(deftest hub-commands-are-forwarded-test
  (with-pair
    (fn [hub app]
      (testing "a /ui write sent to this machine lands in the hub's database"
        (let [{:keys [thrown]} (POST-ui* app "insert-context" [nil {:title "ViaTheServer"}])]
          (is (nil? thrown) (str "the forwarded call failed: " thrown)))
        (let [[_ body] (GET* app "/api/contexts")]
          (is (some #(= "ViaTheServer" (:title %)) body)
              "the context is not in the hub's database, so it was not forwarded"))
        (testing "and it really is the hub's db, not this process's"
          (let [resp (http/get (str (:url hub) "/api/contexts") {:as :string})]
            (is (re-find #"ViaTheServer" (:body resp)))))))))

(deftest machine-local-commands-are-not-forwarded-test
  ;; The classification is empty since Obsidian support was removed, so as in
  ;; db-server.hub-surfaces-test the classification is stubbed and the routing
  ;; is what is tested. Both halves read the same var, and in this test both
  ;; halves are in this JVM -- so the hub would refuse the command if the server
  ;; forwarded it, and that refusal coming back is the tell.
  (with-redefs [placement/machine-local-commands #{"list-resources"}]
    (with-pair
      (fn [_hub app]
        (testing "a machine-local command is answered here, where the files are"
          (let [{:keys [thrown]} (POST-ui* app "list-resources" [{}])]
            (is (not (re-find #"machine-local" (str thrown)))
                (str "this command was forwarded to the hub, which refused it: "
                     thrown))))))))

(deftest unclassified-commands-are-refused-not-guessed-test
  (with-pair
    (fn [_hub app]
      (testing "a command nobody has placed is refused rather than sent somewhere"
        (let [{:keys [thrown return]} (POST-ui* app "not-a-placed-command" [{}])]
          (is (nil? return))
          (is (re-find #"has not been placed" (str thrown))
              (str "expected a placement refusal, got: " (pr-str thrown))))))))

(deftest the-image-question-crosses-and-the-bytes-do-not-test
  ;; /img-by-id is the first route where the outer *calls* the inner rather than
  ;; forwarding to it. The row that says which file lives on the hub; the file
  ;; lives on this machine, in a folder iCloud has already delivered. So the
  ;; question crosses the tunnel and the bytes never do.
  ;;
  ;; The proof of that here is blunt: the file is written into a directory the
  ;; hub knows nothing about, and the hub's own copy of it does not exist. If
  ;; the handler were serving from the hub, there would be nothing to serve.
  (let [dir      (doto (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                           "/rhizome-img-test-" (System/nanoTime)))
                   (.mkdirs) (.deleteOnExit))
        filename "a-picture.png"
        bytes'   (.getBytes "not really a png, but bytes are bytes" "UTF-8")]
    (io/copy bytes' (io/file dir filename))
    (with-pair
      (fn [hub app]
        (jdbc/execute-one!
          (:ds hub)
          ["INSERT INTO items (title, short_title, data, is_context, inserted_at, updated_at, updated_at_ctx)
            VALUES ('An image', '', ?, 0, datetime('now'), datetime('now'), datetime('now'))"
           (json/generate-string {:resource-links {:image filename}})])
        (let [id (:id (jdbc/execute-one! (:ds hub)
                                         ["SELECT id FROM items WHERE title = 'An image'"]
                                         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
          (with-redefs [server/images-folder (.getAbsolutePath dir)]
            (testing "the file is served off this machine's disk"
              (let [resp (app {:request-method :get :uri (str "/img-by-id/" id)
                               :headers {} :body nil})]
                (is (= 200 (:status resp))
                    (str "the handler did not find the image the hub named: " (pr-str resp)))
                (is (= (String. bytes' "UTF-8") (slurp (:body resp))))))
            (testing "a name that would escape the images folder is refused"
              ;; The old handler read straight at the filesystem with no such
              ;; check -- rest-api.queries/image-file called it out by name as
              ;; 'not a model to copy'. This is the check arriving.
              (jdbc/execute-one!
                (:ds hub)
                ["UPDATE items SET data = ? WHERE id = ?"
                 (json/generate-string {:resource-links {:image "../../../etc/passwd"}}) id])
              (is (= 404 (:status (app {:request-method :get :uri (str "/img-by-id/" id)
                                        :headers {} :body nil})))))))))))

(defn- multipart-body
  "A real multipart/form-data body, built by hand.

   By hand because the point is to send the bytes through `server`'s route into
   the hub's own `wrap-multipart-params` -- a mock that hands over a parsed
   `:multipart-params` map would skip the only part of this that could be
   wrong."
  [boundary fields]
  (let [sb (StringBuilder.)]
    (doseq [{:keys [name filename content]} fields]
      (.append sb (str "--" boundary "\r\n"))
      (.append sb (str "Content-Disposition: form-data; name=\"" name "\""
                       (when filename (str "; filename=\"" filename "\"")) "\r\n"))
      (when filename (.append sb "Content-Type: image/png\r\n"))
      (.append sb "\r\n")
      (.append sb content)
      (.append sb "\r\n"))
    (.append sb (str "--" boundary "--\r\n"))
    (.getBytes (.toString sb) "UTF-8")))

(deftest an-upload-lands-on-the-hub-test
  ;; The write direction, and the opposite arrangement to /img-by-id: here the
  ;; bytes travel. An upload writes a file AND the item's resource-links, and
  ;; the two have to agree -- so both happen on the machine that owns the
  ;; database.
  ;;
  ;; The discriminator is the database again: the item exists only in the hub's
  ;; file, so a resource-link appearing on it can only have been written there.
  (with-pair
    (fn [hub app]
      (jdbc/execute-one!
        (:ds hub)
        ["INSERT INTO items (title, short_title, data, is_context, inserted_at, updated_at, updated_at_ctx)
          VALUES ('Needs a preview', '', '{}', 0, datetime('now'), datetime('now'), datetime('now'))"])
      (let [id   (:id (jdbc/execute-one!
                        (:ds hub)
                        ["SELECT id FROM items WHERE title = 'Needs a preview'"]
                        {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))
            bnd  "----rhizometest"
            body (multipart-body bnd [{:name "file" :filename "p.png" :content "pretend-png"}
                                      {:name "id" :content (str id)}
                                      ;; "true" means: do not downscale, so the
                                      ;; test does not depend on ImageMagick
                                      ;; being on the box.
                                      {:name "alternative-behaviour" :content "true"}])
            ;; The local upload handler is made to throw for the duration. It
            ;; is not enough to look at the database: until the statement
            ;; protocol is gone, a locally-handled upload would ALSO write to
            ;; the hub's file, over the wire, and the row would look identical.
            ;; So the routing is asserted where it actually differs -- the local
            ;; handler is not reached at all.
            resp (with-redefs [server/upload-handler
                               (fn [_] (throw (ex-info "the upload was handled locally" {})))]
                   (app {:request-method :post
                         :uri            "/upload"
                         :headers        {"content-type" (str "multipart/form-data; boundary=" bnd)
                                          "content-length" (str (count body))}
                         :content-type   (str "multipart/form-data; boundary=" bnd)
                         :body           (java.io.ByteArrayInputStream. body)}))]
        (is (= 200 (:status resp)) (str "the upload did not go through: " (pr-str resp)))
        (let [row (jdbc/execute-one! (:ds hub)
                                     ["SELECT data FROM items WHERE id = ?" id]
                                     {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})]
          (is (re-find #"preview-image" (str (:data row)))
              (str "the hub's row was not updated, so the upload was handled on "
                   "the wrong machine: " (pr-str (:data row)))))))))

(deftest a-hub-that-is-not-there-answers-502-test
  (testing "a tunnel that is down is an answer, not a stack trace"
    (with-redefs [config/config (assoc config/config
                                  ;; a port nothing is listening on
                                  :hub-url "http://127.0.0.1:1"
                                  :dev? true)]
      (let [[status body] (GET* (server/app) "/api/contexts")]
        (is (= 502 status))
        (is (re-find #"could not be reached" (str (:error body))))))))
