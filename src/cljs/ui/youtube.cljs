(ns ui.youtube
  "One reading of a YouTube address, for everything that has to derive something
   from it.

   Three things are wanted from the address an item carries: the still that
   stands in for the video, the embed the floating player is built from, and --
   untouched, and not from here -- the address itself, which is what a QR code
   has to encode (see ui.qr-overlay). The first two are both a function of the
   video's id, and that id used to be dug out of the URL once per branch of
   item-detail/display-youtube-video, in two different ways. The poster would
   have been a third.

   Only the two forms this app has ever put on a page are read: the watch URL a
   resource link or a description carries, and the shorts/ URL the old embed
   rewrite kept a case for. youtu.be and /embed/ are not among them. An address
   this cannot read is one no video renders for, which is no worse than before:
   the old rewrite built a broken embed out of it and showed that."
  (:require [clojure.string :as str]))

(defn video-id
  "The video's id out of `url`, or nil when it is not an address this can read.

   `v=` is looked for after either separator rather than as the bare
   `watch?v=` prefix the old rewrite assumed: a share link that carries a
   playlist or a start time can put another parameter first, and that link
   pasted into a description used to produce an embed URL with the whole query
   string still hanging off the id."
  [url]
  (when (string? url)
    (let [url (str/trim url)]
      (second (or (re-find #"[?&]v=([A-Za-z0-9_-]+)" url)
                  (re-find #"/shorts/([A-Za-z0-9_-]+)" url))))))

(defn embed-url
  "What the floating player's iframe is built from.

   A pure function of the id, and that is load-bearing rather than tidy: the
   player re-renders on every unrelated change to the app's state, and React
   leaves an iframe alone only for as long as the src it is handed is the
   identical string. Anything varying in here -- a timestamp, a nonce -- would
   reload the video and start it over.

   autoplay=1 because the only way to arrive here is by clicking a poster,
   which is the user gesture the browser's autoplay policy asks for."
  [id]
  (str "https://www.youtube.com/embed/" id "?autoplay=1"))

(defn poster-url
  "YouTube's own still for `id`.

   hqdefault is the largest size that exists for every video: maxresdefault is
   missing for plenty of them and would leave a hole where the video should be.
   It is 4:3 with bars above and below a 16:9 video, which is the frame's
   business to crop -- see .video-poster in main.css."
  [id]
  (str "https://img.youtube.com/vi/" id "/hqdefault.jpg"))
