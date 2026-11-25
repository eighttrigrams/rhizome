(ns et.rz.server.files
  "Every file an item can carry, served to the browser: /files/<name>.

   So that a click on a file can show it in the app rather than send it to
   macOS `open` on the server's machine (/open/), which only ever worked when
   the browser and the server were the same Mac. Hub and server are to run on
   one machine that other machines only look at through a tunnel; whatever a
   click does has to happen in the browser.

   ## Which folder

   The name alone says it. `homefolder/folder-key-for` is what files a name
   under :images, :docs, :audio or :video on import, by its suffix, so asking
   it again here finds the file where import put it -- one place decides, and
   the browser need not know the folders at all. A suffix it does not know is
   404, as is a name without one.

   /imgs/* (wrap-imgs) stays as it is: the image viewer and the previews use it.
   Pictures are also reachable here, so that Download works for every kind.

   ## The name

   Like /imgs/*: the client sends it through encodeURIComponent, so a space, a
   # or a ? arrive intact, and it is url-decoded here (UTF-8). The resolved file
   has to lie inside its folder, by canonical path, or it is 404 -- the name is
   free text out of the database. It is looked for as NFC, then as NFD, so a
   file copied over from a Mac in NFD is found by the NFC name the database
   holds (et.rz.names).

   ## Range

   Ring does not do Range (ring-core 1.15 has nothing for it, and
   `file-response` sends the whole file). A <video> cannot seek without it,
   and Safari will not play at all. So:

   - every answer says `Accept-Ranges: bytes`, the 200 too -- that is the
     answer that tells the browser seeking is possible;
   - one range, `bytes=a-b`, `bytes=a-` or the suffix form `bytes=-n`, is 206
     with `Content-Range`, the end clamped to the file;
   - a range starting at or past the end (and `bytes=-0`, and any range of an
     empty file) is 416 with `Content-Range: bytes */<size>`;
   - a header that does not parse, or asks for several ranges, is ignored, and
     the whole file is sent: RFC 9110 allows that, and it beats a multipart
     body no media element asks for.

   The body is a `FileWindow`, written straight from a FileChannel to the
   response stream: never the file read into memory (a film is gigabytes), and
   no proxy over InputStream (cookbook #131: a missed arity there surfaces as a
   Jetty 500 'written N < M content-length').

   ## Download

   `?download` adds `Content-Disposition: attachment`, with the name both as a
   plain-ASCII fallback and as RFC 5987 `filename*`, so umlauts survive."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.core.protocols :as protocols]
            [ring.util.codec :as codec]
            [ring.util.mime-type :as mime]
            [ring.util.time :as time]
            [et.rz.config :as config]
            [et.rz.names :as names]
            [et.rz.hub.repository.homefolder :as home])
  (:import (java.io File OutputStream)
           (java.net URLEncoder)
           (java.nio.channels Channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(def prefix "/files/")

(def ^:private extra-mime-types
  "What ring's table lacks among the suffixes homefolder files: wav, m4a and
   mkv. mov is there already (video/quicktime)."
  {"wav" "audio/wav"
   "m4a" "audio/mp4"
   "mkv" "video/x-matroska"})

(defn content-type
  [file-name]
  (or (mime/ext-mime-type file-name extra-mime-types)
      "application/octet-stream"))

(defn- folder-key
  "homefolder's folder for the name, or nil. Guarded, because its suffix
   lookup throws on a name without a dot."
  [file-name]
  (when (str/includes? file-name ".")
    (home/folder-key-for file-name)))

(defn resolve-file
  "The file `file-name` names in `folders` (a map like config's :folders), or
   nil when there is none, or it would lie outside its folder."
  [folders file-name]
  (when-let [dir (some->> (folder-key file-name) (get folders))]
    (names/resolve-in dir file-name)))

(defn parse-range
  "The Range header against a file of `size` bytes:
   nil            -- no header, or one to ignore (serve the whole file);
   :unsatisfiable -- 416;
   [start end]    -- inclusive byte positions, end clamped to the file."
  [header size]
  (when-let [[_ from to] (and header (re-matches #"\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*" header))]
    (try
      (cond
        (and (str/blank? from) (str/blank? to)) nil

        ;; bytes=-n, the last n bytes
        (str/blank? from)
        (let [n (Long/parseLong to)]
          (if (or (zero? n) (zero? size))
            :unsatisfiable
            [(max 0 (- size n)) (dec size)]))

        :else
        (let [start (Long/parseLong from)
              end (when-not (str/blank? to) (Long/parseLong to))]
          (cond
            (and end (< end start)) nil
            (>= start size) :unsatisfiable
            :else [start (min (or end (dec size)) (dec size))])))
      ;; digits too many for a long
      (catch NumberFormatException _ nil))))

(defrecord FileWindow [^File file ^long start ^long length])

(extend-protocol protocols/StreamableResponseBody
  FileWindow
  (write-body-to-stream [{:keys [^File file start length]} _response ^OutputStream out]
    (with-open [ch (FileChannel/open (.toPath file) (into-array OpenOption [StandardOpenOption/READ]))
                out out]
      (let [target (Channels/newChannel out)]
        (loop [pos (long start) left (long length)]
          (when (pos? left)
            (let [n (.transferTo ch pos left target)]
              ;; 0 only when the file shrank under us: stop rather than spin.
              (when (pos? n)
                (recur (+ pos n) (- left n))))))))))

(defn- attachment
  [file-name]
  (let [ascii (str/replace file-name #"[^\x20-\x7e]|[\"\\]" "_")]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''"
         ;; URLEncoder writes a space as +, which RFC 5987 does not know, and a
         ;; + of the name as %2B -- so every + it writes is a space. And it
         ;; leaves * alone, which is not an attr-char there.
         (-> (URLEncoder/encode ^String file-name "UTF-8")
             (str/replace "+" "%20")
             (str/replace "*" "%2A")))))

(defn file-response
  "The answer for the file `f`, called `file-name`, to the request `req`."
  [req ^File f file-name]
  (let [size (.length f)
        headers (cond-> {"Accept-Ranges" "bytes"
                         "Content-Type" (content-type file-name)
                         "Last-Modified" (time/format-date (java.util.Date. (.lastModified f)))}
                  (contains? (:query-params req) "download")
                  (assoc "Content-Disposition" (attachment file-name)))
        head? (= :head (:request-method req))
        rng (parse-range (get-in req [:headers "range"]) size)]
    (cond
      (= :unsatisfiable rng)
      {:status 416
       :headers (assoc headers "Content-Range" (str "bytes */" size) "Content-Length" "0")
       :body ""}

      (vector? rng)
      (let [[start end] rng
            length (inc (- end start))]
        {:status 206
         :headers (assoc headers
                         "Content-Range" (str "bytes " start "-" end "/" size)
                         "Content-Length" (str length))
         :body (when-not head? (->FileWindow f start length))})

      :else
      {:status 200
       :headers (assoc headers "Content-Length" (str size))
       :body (when-not head? (->FileWindow f 0 size))})))

(defn handler
  "GET or HEAD /files/<name> against `folders`."
  [folders req]
  (let [file-name (codec/url-decode (subs (:uri req) (count prefix)))]
    (if-let [f (and (not (str/blank? file-name))
                    (resolve-file folders file-name))]
      (file-response req f file-name)
      {:status 404 :headers {"Content-Type" "text/plain"} :body "Not Found"})))

(defn wrap-files
  "Serve /files/* (see the namespace doc); everything else goes on to `handler`.
   `folders` defaults to config's, read per request so a test can bind them."
  ([next-handler] (wrap-files next-handler #(:folders config/config)))
  ([next-handler folders-fn]
   (fn [req]
     (if (and (str/starts-with? (:uri req) prefix)
              (#{:get :head} (:request-method req)))
       (handler (folders-fn) req)
       (next-handler req)))))
