(ns ui.qr-overlay
  "A video's address as a QR code, over the whole page, so it can be carried to
   a phone held up to the screen and watched there.

   What it encodes is the address YouTube itself serves -- the watch or shorts
   URL the item carries -- not the embed/ form the iframe on the page is built
   from. A phone that lands on an embed URL gets a bare player page instead of
   the video in its normal surroundings, so the two must not be confused: the
   caller passes the real one in, and nothing here rewrites it.

   The encoding is qrcode-generator's (see package.json). Only the drawing is
   ours, because the app has no innerHTML idiom to hand the library's own SVG
   string to, and because the two things a QR on a dark page can fail on --
   the quiet zone and the contrast -- are then stated here in the open rather
   than left to a default."
  ;; Plain require, no $default: qrcode-generator is CommonJS and its
  ;; module.exports IS the factory function, so shadow binds it directly. Asking
  ;; for $default here gets an undefined property and fails at the call.
  (:require ["qrcode-generator" :as qrcode]
            [clojure.string :as str]
            [reagent.core :as r]))

(def ^:private quiet-zone
  "Modules of clear margin around the code, on all four sides. Four is what the
   QR spec asks for, and a scanner that cannot find it runs the surrounding page
   into the symbol and fails to lock on."
  4)

(defn- dark-modules
  "The code for `text`, as its module count and the [row col] pairs that are
   dark.

   Type number 0 lets the library pick the smallest version the text fits into.
   Error correction M is the middle setting: it reads back from a phone held at
   an angle without pushing the version up so far that the modules get too fine
   to resolve at the size the overlay shows them."
  [text]
  (let [qr (qrcode 0 "M")]
    (.addData qr text)
    (.make qr)
    (let [n (.getModuleCount qr)]
      {:module-count n
       :dark (for [row (range n) col (range n) :when (.isDark qr row col)] [row col])})))

(defn- code-svg
  "One <path>, one module per 1x1 box, on a white ground. An SVG and not a data
   URL: the overlay sizes the code off the viewport, and a raster at a fixed
   cell size would be resampled to get there."
  [text]
  (let [{:keys [module-count dark]} (dark-modules text)
        side (+ module-count (* 2 quiet-zone))]
    [:svg
     {:viewBox (str "0 0 " side " " side)
      :width "100%"
      :height "100%"
      ;; Module edges land on integer coordinates, so antialiasing them only
      ;; softens the boundary a scanner is looking for.
      :shape-rendering "crispEdges"
      :role "img"
      :aria-label "QR code for the video's address"}
     ;; The white ground covers the quiet zone as well as the code. The overlay
     ;; behind is near-black, and dark modules with nothing light around them
     ;; are not a code, just a texture.
     [:rect {:x 0 :y 0 :width side :height side :fill "#ffffff"}]
     [:path {:fill "#000000"
             :d (str/join " "
                          (map (fn [[row col]]
                                 (str "M" (+ col quiet-zone) "," (+ row quiet-zone) "h1v1h-1z"))
                            dark))}]]))

(defn overlay
  "The code over the whole page, and the ways out of it.

   Public because the floating player puts it up too, and cannot go through
   `component` to do it: it has to render this as a sibling of its own box
   rather than inside it. #floating-player is fixed, clipped and a stacking
   context of its own, so a full-page overlay mounted within it would be cut
   down to the size of the player."
  [url close!]
  (r/create-class
    {;; Focused on mount so the keys arrive here at all. Without it Escape goes
     ;; to #main-layer underneath, which reads it as leave-item-view or
     ;; deselect-context -- the page would rearrange itself behind an overlay
     ;; that stayed up.
     :component-did-mount (fn [_]
                            (when-let [el (.getElementById js/document "qr-overlay")] (.focus el)))
     ;; And handed back on the way out, or the app is left deaf: taking the
     ;; focus is what let this catch Escape, and dropping it on unmount leaves
     ;; it on <body>, where none of the app's keys are listened for. Measured --
     ;; the first Escape closed the overlay and the second one did nothing at
     ;; all. Same move ui/re-focus makes when a modal closes; spelled out here
     ;; rather than called, because ui requires its way down to this namespace.
     :component-will-unmount (fn [_]
                               (when-let [el (.getElementById js/document "main-layer")]
                                 (.focus el)))
     :reagent-render
       (fn [url close!]
         [:div#qr-overlay
          {:tabIndex 0
           ;; Every key is stopped, not just Escape -- the same thing
           ;; ui/handle-mask-keydown does for the modal mask. While this is up
           ;; the app underneath must not be taking keystrokes at all: they
           ;; would act on a list nobody can see.
           :on-key-down (fn [e]
                          (.stopPropagation e)
                          (when (= "Escape" (.-code e))
                            (.preventDefault e)
                            (close!)))
           :on-click (fn [e] (.stopPropagation e) (close!))}
          [:div.qr-overlay-code
           ;; Clicking the code itself does not close: lining a phone up with it
           ;; means moving over it, and a stray click there should not take away
           ;; the thing being aimed at.
           {:on-click #(.stopPropagation %)} [code-svg url]]
          [:button.qr-overlay-close
           {:type "button" :title "Close (Esc)" :aria-label "Close" :on-click (fn [e]
                                                                               (.stopPropagation e)
                                                                               (close!))} "✕"]])}))

(defn icon
  "A QR code at a glance, at a size that reads as a control rather than as
   content. Public for the same reason `overlay` is: the player draws its own."
  []
  [:svg {:width 15 :height 15 :viewBox "0 0 16 16" :aria-hidden "true"}
   [:g {:fill "currentColor"}
    ;; Three finder squares and a scatter of modules.
    [:path {:d "M0 0h6v6H0V0zm1 1v4h4V1H1z"}] [:path {:d "M10 0h6v6h-6V0zm1 1v4h4V1h-4z"}]
    [:path {:d "M0 10h6v6H0v-6zm1 1v4h4v-4H1z"}] [:rect {:x 2 :y 2 :width 2 :height 2}]
    [:rect {:x 12 :y 2 :width 2 :height 2}] [:rect {:x 2 :y 12 :width 2 :height 2}]
    [:rect {:x 8 :y 8 :width 2 :height 2}] [:rect {:x 12 :y 8 :width 2 :height 2}]
    [:rect {:x 8 :y 12 :width 2 :height 2}] [:rect {:x 14 :y 14 :width 2 :height 2}]]])

(defn component
  "The icon that sits under a video's poster, and the overlay it opens.

   Whether it is open is kept here, per mounted icon, and not in the app's
   *state: it is about one video on one screen, nothing else in the app has to
   know, and an overlay left standing in shared state would be waiting on the
   next item that happened to have a video.

   The floating player offers the same thing for whatever it is playing, and
   composes `icon` and `overlay` itself rather than mounting this -- see
   ui.floating-player. Local state is right for it too, and for the same reason
   read the other way round: an open overlay there is about the player's own
   screen, which is why it can be nothing but local even though what the player
   is *playing* has to be shared."
  [_url]
  (let [*open? (r/atom false)]
    (fn [url]
      [:<>
       [:span.qr-open
        {:title "Show a QR code for this video, to carry it to a phone"
         :role "button"
         :on-click (fn [e] (.stopPropagation e) (reset! *open? true))} [icon]]
       (when @*open? [overlay url #(reset! *open? false)])])))
