(ns rest-api.item-images-test
  "GET /api/items/:id/images — the one endpoint that reads the image folders.

   Its own namespace rather than a block in queries-test because every test here
   needs a filesystem: the endpoint's job is half path derivation, and the three
   image kinds live at three unrelated paths. So the folders are real temp
   directories with real bytes in them and nothing here is mocked. `config/config`
   is redefined per request to point :folders at them, the same way queries-test
   redefines it to point :db at the test database."
  (:require [clojure.test :refer [deftest is testing]]
            [db-harness]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [config :as config]
            [rest-api :as rest-api]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [reset-db with-time db]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(def ^:private handler (delay (wrap-params (rest-api/rest-routes))))

;; A real 1x1 png. Used for the main round-trip assertion specifically because
;; it is real binary -- bytes above 0x7F, a null, the lot. Base64 that survives
;; this survives an actual photograph; base64 of ASCII test bytes would prove
;; much less.
(def ^:private png-1x1
  (.decode (Base64/getDecoder)
           (str "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
                "z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==")))

(defn- temp-dir [] (str (.toFile (Files/createTempDirectory "rhizome-imgs" (into-array FileAttribute [])))))

(defn- folders!
  "Fresh :images and :preview-images roots, with the Lowres/ subfolder the
   endpoint expects under the latter."
  []
  (let [images (temp-dir)
        previews (temp-dir)]
    (.mkdirs (io/file previews "Lowres"))
    {:images images :preview-images previews}))

(defn- write-bytes!
  [^String dir ^String filename ^bytes content]
  (let [f (io/file dir filename)]
    (io/make-parents f)
    (with-open [out (io/output-stream f)] (.write out content))
    f))

(defn- bytes-of [n seed]
  (byte-array (map #(byte (- (mod (+ seed %) 251) 125)) (range n))))

(defn- GET*
  "The only place in this file that hands a handler a config: `:folders` on top
   of `db-harness/app-config`, whose `:db` is the remote handle.
   `api.harness-wiring-test` drives this very function to prove that."
  [folders path]
  (with-redefs [config/config (db-harness/app-config {:folders folders})]
    (@handler (mock/request :get path))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(defn- item-with-data!
  "An item carrying `data`. new-item insists on a context, so one is made for it."
  [data]
  (let [ctx (ds/new-context db {:title "Pictures"})
        item (ds/new-item db "An item with images" "" #{(:id ctx)} 1)]
    (ds/update-item db (assoc item :data data))
    (:id item)))

(defn- by-kind [body] (into {} (map (juxt :kind identity)) (:images body)))

(defn- decoded [entry] (.decode (Base64/getDecoder) ^String (:data-base64 entry)))

(defmacro ^:private test-fresh
  [description & body]
  `(testing ~description
     (reset-db)
     (with-time ~@body)))

;; --- the happy path ----------------------------------------------------------

(deftest all-three-kinds-test
  (test-fresh "an item carrying all three kinds returns all three, bytes intact"
    (let [{:keys [images preview-images] :as folders} (folders!)
          id (item-with-data! {:resource-links {:image "Sketch.png" :file "Sketch.png"}
                               :lowres? false})]
      ;; The previews are named after the item id, which is only known now.
      (ds/update-item db (assoc (ds/get-item db {:id id})
                                :data {:preview-image (str id ".png")
                                       :preview-image-lowres (str id ".png")}))
      (let [own (bytes-of 300 7)
            hi (bytes-of 900 11)]
        (write-bytes! images "Sketch.png" own)
        (write-bytes! preview-images (str id ".png") hi)
        (write-bytes! (str (io/file preview-images "Lowres")) (str id ".png") png-1x1)
        (let [resp (GET* folders (str "/api/items/" id "/images?data=true"))
              body (body-json resp)
              entries (by-kind body)]
          (is (= 200 (:status resp)))
          (is (= #{"image" "preview-highres" "preview-lowres"} (set (keys entries))))
          (is (= [] (:missing body)) "nothing declared is absent")

          (testing "the bytes survive the base64 round trip exactly"
            (is (= (seq own) (seq (decoded (entries "image")))))
            (is (= (seq hi) (seq (decoded (entries "preview-highres")))))
            (is (= (seq png-1x1) (seq (decoded (entries "preview-lowres"))))
                "real png binary, high bytes and nulls included"))

          (testing "bytes is the size of the file, not of its base64"
            (is (= 300 (:bytes (entries "image"))))
            (is (= 900 (:bytes (entries "preview-highres"))))
            (is (= (alength png-1x1) (:bytes (entries "preview-lowres"))))
            (is (< (:bytes (entries "preview-highres"))
                   (count (:data-base64 (entries "preview-highres"))))
                "the encoding really is larger, so the two cannot be confused"))

          (testing "each kind reports the url wrap-imgs serves it at"
            (is (= "/imgs/Sketch.png" (:url (entries "image"))))
            (is (= (str "/imgs/Preview/" id ".png") (:url (entries "preview-highres"))))
            (is (= (str "/imgs/Preview/Lowres/" id ".png")
                   (:url (entries "preview-lowres")))))

          (testing "lowres? is echoed as the UI's tie-breaker"
            (is (false? (:lowres? body)))))))))

(deftest manifest-gear-test
  (test-fresh "without data=true the answer carries no bytes but still sizes them"
    (let [folders (folders!)
          id (item-with-data! {:resource-links {:image "photo.png"}})]
      (write-bytes! (:images folders) "photo.png" (bytes-of 4096 3))
      (let [body (body-json (GET* folders (str "/api/items/" id "/images")))
            entry (get (by-kind body) "image")]
        (is (= 4096 (:bytes entry)))
        (is (not (contains? entry :data-base64))
            "the cheap gear is cheap: no payload at all")))))

;; --- the two traps the endpoint exists to handle honestly --------------------

(deftest both-previews-live-test
  (test-fresh "both preview files on disk means both reported, not one"
    ;; update-item merges :data, so upload's dissoc of the other preview key
    ;; never lands and an item really can hold both. The UI picks one via
    ;; :lowres?; this endpoint must not.
    (let [{:keys [preview-images] :as folders} (folders!)
          id (item-with-data! {:lowres? true})]
      (ds/update-item db (assoc (ds/get-item db {:id id})
                                :data {:preview-image (str id ".png")
                                       :preview-image-lowres (str id ".png")}))
      (write-bytes! preview-images (str id ".png") (bytes-of 500 1))
      (write-bytes! (str (io/file preview-images "Lowres")) (str id ".png") (bytes-of 60 2))
      (let [body (body-json (GET* folders (str "/api/items/" id "/images")))
            entries (by-kind body)]
        (is (= 2 (count (:images body))))
        (is (= #{"preview-highres" "preview-lowres"} (set (keys entries))))
        (is (= 500 (:bytes (entries "preview-highres"))))
        (is (= 60 (:bytes (entries "preview-lowres"))))
        (is (true? (:lowres? body))
            "the tie-breaker is reported, but it did not suppress anything")))))

(deftest declared-but-absent-test
  (test-fresh "a data key naming a file that is not there is reported, not raised"
    (let [{:keys [images] :as folders} (folders!)
          id (item-with-data! {:resource-links {:image "here.png"}
                               :preview-image "gone.png"})]
      (write-bytes! images "here.png" (bytes-of 10 5))
      (let [resp (GET* folders (str "/api/items/" id "/images?data=true"))
            body (body-json resp)]
        (is (= 200 (:status resp)) "an unmounted volume is not a server error")
        (is (= ["image"] (map :kind (:images body)))
            "images holds exactly what can be fetched")
        (is (= [{:kind "preview-highres" :filename "gone.png"}] (:missing body)))))))

(deftest unconfigured-folder-test
  (test-fresh "a folder missing from config makes its kind missing, not a 500"
    (let [id (item-with-data! {:resource-links {:image "orphan.png"}})
          body (body-json (GET* {} (str "/api/items/" id "/images")))]
      (is (= [] (:images body)))
      (is (= ["image"] (map :kind (:missing body)))))))

;; --- filters and refusals ----------------------------------------------------

(deftest kinds-filter-test
  (test-fresh "kinds narrows the answer to what was asked for"
    (let [{:keys [images preview-images] :as folders} (folders!)
          id (item-with-data! {:resource-links {:image "big.png"}})]
      (ds/update-item db (assoc (ds/get-item db {:id id})
                                :data {:preview-image-lowres (str id ".png")}))
      (write-bytes! images "big.png" (bytes-of 5000 9))
      (write-bytes! (str (io/file preview-images "Lowres")) (str id ".png") (bytes-of 40 4))
      (let [body (body-json (GET* folders (str "/api/items/" id
                                               "/images?kinds=preview-lowres&data=true")))]
        (is (= ["preview-lowres"] (map :kind (:images body))))
        (is (= [] (:missing body))
            "a kind that was filtered out is not then reported as missing")
        (is (= 40 (:bytes (first (:images body))))
            "the point of the filter: 40 bytes instead of 5000")))))

(deftest unknown-kind-refused-test
  (test-fresh "an unrecognised kind is refused rather than ignored"
    (let [folders (folders!)
          id (item-with-data! {:resource-links {:image "x.png"}})
          resp (GET* folders (str "/api/items/" id "/images?kinds=thumbnail"))
          body (body-json resp)]
      (is (= 400 (:status resp)))
      (is (re-find #"thumbnail" (:error body)))
      (is (= ["image" "preview-highres" "preview-lowres"] (:valid-kinds body))
          "the refusal names the valid kinds, so a caller need not guess"))))

(deftest no-images-test
  (test-fresh "an item with no images answers 200 and an empty list"
    (let [folders (folders!)
          id (item-with-data! {})
          resp (GET* folders (str "/api/items/" id "/images"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= [] (:images body)))
      (is (= [] (:missing body)))
      (is (and (contains? body :images) (contains? body :missing))
          "both always present, so neither needs a nil check"))))

(deftest missing-item-404-test
  (test-fresh "a nonexistent id is 404, unlike GET /api/items/:id"
    (let [folders (folders!)
          resp (GET* folders "/api/items/99999999/images")]
      (is (= 404 (:status resp)))
      (is (= "Item not found" (:error (body-json resp))))
      (testing "and the endpoint it deliberately differs from still does its thing"
        (is (= 200 (:status (GET* folders "/api/items/99999999"))))))))

(deftest bad-id-400-test
  (test-fresh "a non-numeric id is a 400"
    (let [resp (GET* (folders!) "/api/items/not-a-number/images")]
      (is (= 400 (:status resp)))
      (is (= "Invalid item ID" (:error (body-json resp)))))))

;; --- filenames --------------------------------------------------------------

(deftest content-type-from-suffix-test
  (test-fresh "the item's own image can be any suffix; the type follows it"
    (doseq [[filename expected] {"a.jpg" "image/jpeg"
                                 "b.JPEG" "image/jpeg"
                                 "c.webp" "image/webp"
                                 "d.PNG" "image/png"
                                 "e.tiff" "application/octet-stream"
                                 "no-suffix" "application/octet-stream"}]
      (let [{:keys [images] :as folders} (folders!)
            id (item-with-data! {:resource-links {:image filename}})]
        (write-bytes! images filename (bytes-of 8 1))
        (let [body (body-json (GET* folders (str "/api/items/" id "/images")))]
          (is (= expected (:content-type (first (:images body))))
              (str filename " -> " expected)))))))

(deftest spaced-filename-test
  (test-fresh "a filename with a space is served and its url is percent-encoded"
    (let [{:keys [images] :as folders} (folders!)
          content (bytes-of 64 6)
          id (item-with-data! {:resource-links {:image "Screen Shot 2026.png"}})]
      (write-bytes! images "Screen Shot 2026.png" content)
      (let [body (body-json (GET* folders (str "/api/items/" id "/images?data=true")))
            entry (first (:images body))]
        (is (= "Screen Shot 2026.png" (:filename entry))
            "filename stays as it is on disk")
        (is (= "/imgs/Screen%20Shot%202026.png" (:url entry))
            "the url is encoded, so it is usable as given")
        (is (= (seq content) (seq (decoded entry))))))))

(deftest no-escaping-the-root-test
  (test-fresh "a filename that would climb out of its folder is refused"
    ;; The name comes from the database rather than the request, but it is still
    ;; free-form text, and this read goes straight at the filesystem without
    ;; ring's :root to guard it.
    (let [{:keys [images] :as folders} (folders!)
          outside (write-bytes! (temp-dir) "secret.png" (bytes-of 16 8))
          id (item-with-data! {:resource-links
                               {:image (str "../" (.getName (.getParentFile outside))
                                            "/secret.png")}})
          body (body-json (GET* folders (str "/api/items/" id "/images?data=true")))]
      (is (.exists outside) "the file really is there to be reached")
      (is (= [] (:images body)) "and it was not served")
      (is (= 1 (count (:missing body)))
          "an escaping name is reported the same way an absent one is"))))
