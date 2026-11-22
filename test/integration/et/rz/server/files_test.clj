(ns et.rz.server.files-test
  "/files/<name>: a whole file, a range, a range past the end, a traversal,
   the content types and the download header.

   Most of it is asked of the middleware with ring-mock, and each body is
   drained through the protocol Jetty itself uses, so a Content-Length that
   promises more than is written fails here (cookbook #131). One test goes
   through a real Jetty, because the adapter writing a FileWindow at all is
   the part ring-mock cannot show."
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [ring.middleware.params :refer [wrap-params]]
            [ring.mock.request :as mock]
            [et.rz.server.files :as files])
  (:import (java.io ByteArrayOutputStream File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private content
  "300 bytes, each its own position mod 256, so a window's bytes say where
   they came from."
  (byte-array (map unchecked-byte (range 300))))

(def ^:dynamic *root* nil)

(defn- folders []
  {:docs (str (io/file *root* "docs"))
   :audio (str (io/file *root* "audio"))
   :video (str (io/file *root* "video"))
   :images (str (io/file *root* "images"))})

(defn- write! [k file-name bytes]
  (let [f (io/file (get (folders) k) file-name)]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o ^bytes bytes))
    f))

(use-fixtures :each
  (fn [t]
    (let [root (.toFile (Files/createTempDirectory "rhizome-files-test" (make-array FileAttribute 0)))]
      (binding [*root* root]
        (doseq [k [:docs :audio :video :images]] (.mkdirs (io/file (get (folders) k))))
        (write! :video "clip.mp4" content)
        (try (t)
             (finally
               (doseq [^File f (reverse (file-seq root))] (.delete f))))))))

(defn- app
  "Inside wrap-params, as in et.rz.server.main/app: ?download is read from
   :query-params."
  []
  (wrap-params (files/wrap-files (fn [_] {:status 418 :body "not mine"}) folders)))

(defn- body-bytes
  "The body as Jetty would write it."
  [{:keys [body] :as response}]
  (let [out (ByteArrayOutputStream.)]
    (when body (protocols/write-body-to-stream body response out))
    (.toByteArray out)))

(defn- get-file
  ([path] (get-file path nil))
  ([path range]
   (let [res ((app) (cond-> (mock/request :get path)
                      range (mock/header "Range" range)))]
     (assoc res :bytes (body-bytes res)))))

(defn- slice [from to-inclusive]
  (vec (java.util.Arrays/copyOfRange ^bytes content (int from) (int (inc to-inclusive)))))

(deftest a-whole-file
  (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4")]
    (is (= 200 status))
    (is (= (vec content) (vec bytes)))
    (is (= "300" (headers "Content-Length")))
    (testing "the 200 says seeking is possible"
      (is (= "bytes" (headers "Accept-Ranges"))))
    (is (= "video/mp4" (headers "Content-Type")))
    (is (nil? (headers "Content-Disposition")))))

(deftest a-range
  (testing "an explicit window"
    (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" "bytes=10-19")]
      (is (= 206 status))
      (is (= "bytes 10-19/300" (headers "Content-Range")))
      (is (= "10" (headers "Content-Length")))
      (is (= (slice 10 19) (vec bytes)))
      (is (= "bytes" (headers "Accept-Ranges")))))
  (testing "from a position to the end"
    (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" "bytes=290-")]
      (is (= 206 status))
      (is (= "bytes 290-299/300" (headers "Content-Range")))
      (is (= (slice 290 299) (vec bytes)))))
  (testing "the last n bytes"
    (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" "bytes=-5")]
      (is (= 206 status))
      (is (= "bytes 295-299/300" (headers "Content-Range")))
      (is (= (slice 295 299) (vec bytes)))))
  (testing "an end past the file is cut to the file"
    (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" "bytes=250-100000")]
      (is (= 206 status))
      (is (= "bytes 250-299/300" (headers "Content-Range")))
      (is (= "50" (headers "Content-Length")))
      (is (= 50 (count bytes)))))
  (testing "more bytes asked from the end than there are: the whole file"
    (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" "bytes=-1000")]
      (is (= 206 status))
      (is (= "bytes 0-299/300" (headers "Content-Range")))
      (is (= (vec content) (vec bytes))))))

(deftest a-range-out-of-bounds
  (doseq [r ["bytes=300-" "bytes=1000-2000" "bytes=-0"]]
    (testing r
      (let [{:keys [status headers bytes]} (get-file "/files/clip.mp4" r)]
        (is (= 416 status))
        (is (= "bytes */300" (headers "Content-Range")))
        (is (zero? (count bytes)))))))

(deftest a-range-to-ignore
  (doseq [r ["bytes=20-10" "bytes=0-1,5-6" "lines=1-2" "bytes=abc" "bytes=99999999999999999999-"]]
    (testing r
      (let [{:keys [status bytes]} (get-file "/files/clip.mp4" r)]
        (is (= 200 status))
        (is (= 300 (count bytes)))))))

(deftest a-traversal
  ;; A file beside the folders, that the traversal would reach.
  (spit (io/file *root* "secret.pdf") "secret")
  (doseq [path ["/files/..%2Fsecret.pdf"
                "/files/../secret.pdf"
                "/files/%2E%2E%2Fsecret.pdf"
                "/files/..%2F..%2Fsecret.pdf"]]
    (testing path
      (is (= 404 (:status (get-file path))))))
  (testing "nor through a folder whose name starts with the docs folder's"
    (let [beside (io/file (str (get (folders) :docs) "-other"))]
      (.mkdirs beside)
      (spit (io/file beside "x.pdf") "x")
      (is (= 404 (:status (get-file (str "/files/..%2Fdocs-other%2Fx.pdf"))))))))

(deftest a-symlink-out-of-the-folder-is-not-found
  ;; Names with a separator are refused before the canonical check; a link
  ;; inside the folder that points out of it is what the check is still for.
  (spit (io/file *root* "secret.pdf") "secret")
  (java.nio.file.Files/createSymbolicLink (.toPath (io/file (get (folders) :docs) "link.pdf"))
                                          (.toPath (io/file *root* "secret.pdf"))
                                          (make-array FileAttribute 0))
  (is (= 404 (:status (get-file "/files/link.pdf")))))

(deftest an-absolute-path-is-not-found
  ;; Review finding 2: io/file threw IllegalArgumentException on an absolute
  ;; child, and the request got a 500.
  (spit (io/file *root* "secret.pdf") "secret")
  (doseq [path [(str "/files/" (java.net.URLEncoder/encode (str (io/file *root* "secret.pdf")) "UTF-8"))
                "/files/%2Fetc%2Fpasswd.pdf"]]
    (testing path
      (is (= 404 (:status (get-file path)))))))

(deftest not-found
  (doseq [path ["/files/" "/files/nope.mp4" "/files/noext" "/files/clip.xyz"]]
    (testing path
      (is (= 404 (:status (get-file path)))))))

(deftest only-its-own-prefix
  (is (= 418 (:status ((app) (mock/request :get "/filesx/clip.mp4")))))
  (is (= 418 (:status ((app) (mock/request :post "/files/clip.mp4"))))))

(def ^:private utf-8-paths?
  "Whether this JVM can name files outside ASCII at all. Under a POSIX locale
   (LANG unset, as in the rhizome box) sun.jnu.encoding is ASCII and it cannot:
   every such name is unreachable, here as at /imgs/*."
  (= "UTF-8" (System/getProperty "sun.jnu.encoding")))

(deftest names-are-free-text
  (doseq [file-name (cond-> ["Brief an Max #2 ? 50% + mehr.pdf"]
                      utf-8-paths? (conj "Brief an Jürgen #2.pdf"))]
    (write! :docs file-name (.getBytes "pdf"))
    (let [res (get-file (str "/files/" (java.net.URLEncoder/encode file-name "UTF-8")))]
      ;; URLEncoder writes a space as +, which a path does not decode; the
      ;; browser's encodeURIComponent writes %20.
      (is (= 404 (:status res))))
    (let [encoded (.replace (java.net.URLEncoder/encode file-name "UTF-8") "+" "%20")
          {:keys [status bytes]} (get-file (str "/files/" encoded))]
      (is (= 200 status))
      (is (= "pdf" (String. ^bytes bytes "UTF-8"))))))

(deftest content-types
  (doseq [[file-name k ct] [["a.pdf" :docs "application/pdf"]
                            ["a.mp3" :audio "audio/mpeg"]
                            ["a.wav" :audio "audio/wav"]
                            ["a.ogg" :audio "audio/ogg"]
                            ["a.m4a" :audio "audio/mp4"]
                            ["a.mp4" :video "video/mp4"]
                            ["a.MOV" :video "video/quicktime"]
                            ["a.flv" :video "video/x-flv"]
                            ["a.tiff" :docs "image/tiff"]
                            ["a.jpg" :images "image/jpeg"]]]
    (write! k file-name (.getBytes "x"))
    (testing file-name
      (let [{:keys [status headers]} (get-file (str "/files/" file-name))]
        (is (= 200 status))
        (is (= ct (headers "Content-Type")))))))

(deftest download
  (write! :docs "Brief \"neu\".pdf" (.getBytes "pdf"))
  (let [{:keys [status headers]} ((app) (mock/request :get "/files/Brief%20%22neu%22.pdf" {"download" ""}))]
    (is (= 200 status))
    (is (= (str "attachment; filename=\"Brief _neu_.pdf\"; "
                "filename*=UTF-8''Brief%20%22neu%22.pdf")
           (headers "Content-Disposition")))))

(deftest download-header-encodes-what-rfc-5987-does-not-allow
  ;; * is no attr-char; URLEncoder leaves it be (review finding 5).
  (is (= "attachment; filename=\"a*b.pdf\"; filename*=UTF-8''a%2Ab.pdf"
         (#'files/attachment "a*b.pdf"))))

(deftest download-header-of-a-name-outside-ascii
  ;; The header alone, so it is tested whatever the JVM's path encoding.
  (is (= (str "attachment; filename=\"J_rgen + 1.pdf\"; "
              "filename*=UTF-8''J%C3%BCrgen%20%2B%201.pdf")
         (#'files/attachment "Jürgen + 1.pdf"))))

(deftest head-request
  (let [res ((app) (-> (mock/request :head "/files/clip.mp4") (mock/header "Range" "bytes=0-9")))]
    (is (= 206 (:status res)))
    (is (= "10" (get-in res [:headers "Content-Length"])))
    (is (nil? (:body res)))))

(deftest through-jetty
  ;; Jetty's threads do not see the binding of *root*, so the folders are
  ;; taken now.
  (let [fs (folders)
        server (jetty/run-jetty (files/wrap-files (fn [_] {:status 418}) (constantly fs))
                                {:port 0 :host "127.0.0.1" :join? false})
        port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors server)))
        url (str "http://127.0.0.1:" port "/files/clip.mp4")]
    (try
      (let [res (http/get url {:as :byte-array :headers {"Range" "bytes=100-149"}})]
        (is (= 206 (:status res)))
        (is (= (slice 100 149) (vec (:body res)))))
      (let [res (http/get url {:as :byte-array})]
        (is (= 200 (:status res)))
        (is (= (vec content) (vec (:body res)))))
      (let [res (http/get url {:headers {"Range" "bytes=400-"} :throw-exceptions false})]
        (is (= 416 (:status res))))
      (finally (.stop server)))))
