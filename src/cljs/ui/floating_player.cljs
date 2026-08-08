(ns ui.floating-player
  "The one player. A video plays here or it does not play: the item detail shows
   a still and hands the address over (see item-detail/display-youtube-video),
   and what starts here goes on running while the owner edits, searches, or
   moves to another item entirely. Outliving the view that started it is the
   whole feature.

   ## Why what is playing lives in *state, where ui.qr-overlay's flag does not

   ui.qr-overlay keeps its `*open?` in a component-local r/atom and says why: an
   overlay is about one video on one screen, and one left standing in shared
   state would be waiting on the next item that happened to have a video. That
   is right there and exactly inverted here. Navigate away and the component
   that started the video is gone; a flag held in it would go too, and the
   player would die of the very thing it exists to survive. So the video's
   identity is the app's, and it is kept in *state.

   Which corner it sits in is local again, and for a reason that is not
   symmetry. The player is mounted once for the life of the page, so a local
   atom is every bit as durable as *state for it -- and *state is the riskier of
   the two, because every dispatch response reset!s that atom from a snapshot
   taken when the request went out (ui.actions.common/fetch-and-reset!). A
   corner set mid-drag could be undone by a search that was already in flight.
   The same hazard would reach the video's identity, which cannot be kept out of
   *state; reset-state! carries it across instead, and says so.

   ## The iframe is mounted once and never moved

   Moving an <iframe> in the DOM reloads it. Re-parent it, or let React unmount
   and remount it, and playback starts from zero -- so the player hangs off #ui
   beside #main-layer, where the always-present layers live, and stays there for
   as long as the page does. Changing corners is `top`/`left` on a
   `position: fixed` box and nothing else: never a move in the tree.

   Its src is a pure function of the video's id (ui.youtube/embed-url), so every
   unrelated re-render hands React the identical string and React leaves the
   element alone. A second video is the one thing that does change it, and
   reloading is then exactly what is wanted.

   ## Why the player carries a QR of its own

   The poster has one too, and this is not a copy of it. A QR is for handing the
   video to a phone, and the moment the owner navigates away -- which is the
   situation this whole feature exists to create -- the icon under the poster is
   about an item that is no longer on screen. The one here is about what is
   playing, which by then is routinely a different video, and it is the only one
   that can still reach it.

   That is also why what is playing is kept as the address the item carried and
   not only as the id: the code has to encode the address YouTube serves, and a
   watch URL rebuilt out of an id would be this app's guess at one rather than
   the thing the item said. ui.qr-overlay is emphatic about not rewriting what it
   is given, and the handover is exactly where a rewrite would be tempting."
  (:require [reagent.core :as r]
            [ui.qr-overlay :as qr-overlay]
            [ui.youtube :as youtube]))

(def ^:private frame-width
  "Wide enough to watch, narrow enough to leave the app usable behind it. The
   height is this at 16:9, which is the shape of the video rather than of
   YouTube's still."
  420)

(def ^:private frame-height 236)

(def ^:private handle-height
  "The grip strip. Mirrored in .floating-player-handle; kept here as well
   because the drag has to know how tall the whole box is to find its centre."
  24)

(def ^:private corner-padding
  "Clear of the corner rather than jammed into it -- the corner badges live
   there too, and a player flush against the edge reads as an accident."
  20)

(defn play!
  "Start `url` in the player, replacing whatever was playing.

   Both halves are kept: the id the src is built from, and `url` itself, which
   is the address the item carried and the only thing the player's QR code may
   encode. Nothing happens for an address ui.youtube cannot read -- there would
   be no video to show, and taking the running one down for it would be worse
   than ignoring the click."
  [*state url]
  (when-let [id (youtube/video-id url)]
    (swap! *state assoc :playing-video {:id id :url url})))

(defn close! [*state] (swap! *state dissoc :playing-video))

(defn- corner-position
  "The fixed-position offsets for a corner, as CSS.

   calc against the viewport rather than pixels measured from js/window: the
   browser recomputes it on a resize, so the player cannot end up hanging off
   an edge that moved while it sat there, and nothing has to listen for it.

   The top offsets carry --top-strip-height the way every other floating marker
   in this app does (see ui.replica/banner): hierarchy mode takes a row off the
   top of the viewport, and 0px the rest of the time leaves this plain
   corner-padding."
  [corner]
  (let [pad (str corner-padding "px")
        from-bottom (str "calc(100vh - " (+ frame-height handle-height corner-padding) "px)")
        from-right (str "calc(100vw - " (+ frame-width corner-padding) "px)")]
    {:top (if (#{:top-left :top-right} corner)
            (str "calc(" pad " + var(--top-strip-height))")
            from-bottom)
     :left (if (#{:top-left :bottom-left} corner) pad from-right)}))

(defn- quadrant
  "The corner the box belongs in, from where its centre was let go."
  [centre-x centre-y]
  (keyword (str (if (< centre-y (/ (.-innerHeight js/window) 2)) "top" "bottom")
                "-"
                (if (< centre-x (/ (.-innerWidth js/window) 2)) "left" "right"))))

(defn- begin-drag!
  [*drag e]
  ;; The handle keeps the pointer for the whole gesture. Without it the first
  ;; move that crosses the video is delivered into the iframe -- a cross-origin
  ;; document, which never says a word back -- and the drag dies halfway across
  ;; the player it is dragging. Measured: with capture the pointer can be swept
  ;; anywhere on the page, the iframe included, and every move still arrives.
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  ;; Or the gesture starts selecting the grip's text instead of moving the box.
  (.preventDefault e)
  (let [rect (.getBoundingClientRect (.getElementById js/document "floating-player"))]
    (reset! *drag {:left (.-left rect)
                   :top (.-top rect)
                   :grab-x (- (.-clientX e) (.-left rect))
                   :grab-y (- (.-clientY e) (.-top rect))})))

(defn- drag-to!
  [*drag e]
  (when-let [{:keys [grab-x grab-y]} @*drag]
    (swap! *drag assoc
      :left (- (.-clientX e) grab-x)
      :top (- (.-clientY e) grab-y))))

(defn- settle!
  "Let go: the box travels to the corner of whichever quadrant its centre is in.
   Clearing the drag is what puts the CSS transition back in charge, so the last
   stretch is movement rather than a jump."
  [*drag *corner e]
  (when-let [{:keys [left top]} @*drag]
    (let [el (.-currentTarget e)]
      (when (.hasPointerCapture el (.-pointerId e))
        (.releasePointerCapture el (.-pointerId e))))
    (reset! *corner (quadrant (+ left (/ frame-width 2))
                              (+ top (/ (+ frame-height handle-height) 2))))
    (reset! *drag nil)))

(defn- grip
  []
  [:svg.floating-player-grip {:width 22 :height 10 :viewBox "0 0 22 10" :aria-hidden "true"}
   [:g {:fill "currentColor"}
    (for [x [3 8 13 18] y [3 6]]
      ^{:key (str x "-" y)} [:circle {:cx x :cy y :r 1}])]])

(defn component
  "Mounted once, from ui/component, and never anywhere else."
  [_*state]
  (let [;; Bottom left, and by the owner's decision. It is also the one corner
        ;; nothing else is already in: the ⚠ REC badge and the vector-threshold
        ;; panel are top left, ⚠ DANGER is top right. So a video opens beside
        ;; the app's own markers rather than on top of one.
        *corner (r/atom :bottom-left)
        *drag (r/atom nil)
        *qr-open? (r/atom false)]
    (fn [*state]
      (when-let [{:keys [id url]} (:playing-video @*state)]
        ;; A fragment, so the overlay is a sibling of the player's box and not a
        ;; child of it. #floating-player is fixed, clipped and a stacking
        ;; context of its own; a full-page overlay inside it would be cut down
        ;; to 420px of player.
        [:<>
         [:div#floating-player
          {:style (if-let [{:keys [left top]} @*drag]
                    ;; Under the pointer, and with the transition off, or the
                    ;; box would lag its own drag by the settling animation.
                    {:left (str left "px") :top (str top "px") :transition "none"}
                    (corner-position @*corner))}
          [:div.floating-player-handle
           {:title "Drag the player to another corner"
            :on-pointer-down #(begin-drag! *drag %)
            :on-pointer-move #(drag-to! *drag %)
            :on-pointer-up #(settle! *drag *corner %)
            ;; A cancelled pointer (the OS taking over, a touch turning into a
            ;; scroll) would otherwise leave the box wherever it was and the
            ;; drag still believing it is running.
            :on-pointer-cancel #(settle! *drag *corner %)} [grip]
           [:span.floating-player-controls
            ;; Neither control may start a drag. A pointerdown reaching the
            ;; strip captures the pointer, and the click that follows is then
            ;; delivered to the capturing element rather than to the button
            ;; under the finger.
            {:on-pointer-down #(.stopPropagation %)}
            [:span.qr-open.floating-player-qr
             {:title "Show a QR code for the video playing here, to carry it to a phone"
              :role "button"
              :on-click (fn [e] (.stopPropagation e) (reset! *qr-open? true))} [qr-overlay/icon]]
            [:button.floating-player-close
             {:type "button"
              :title "Close the player"
              :aria-label "Close the player"
              :on-click (fn [e]
                          (.stopPropagation e)
                          ;; The code goes with the player. The X is above the
                          ;; overlay and can be pressed while one is up, and
                          ;; this component is mounted for the life of the page
                          ;; -- so a flag left true here is still true when the
                          ;; next video starts, and the overlay would come back
                          ;; up on its own over a video nobody asked to share.
                          (reset! *qr-open? false)
                          (close! *state))} "✕"]]]
          [:iframe#floating-player-frame
           {:src (youtube/embed-url id)
            :width frame-width
            :height frame-height
            ;; autoplay has to be granted to the frame as well as asked for in
            ;; the URL; without it the policy stops the video at the boundary
            ;; and the player comes up paused. fullscreen is in the same list
            ;; rather than as the allowfullscreen attribute the inline embed
            ;; carried: with both, Chromium logs that this one takes precedence
            ;; anyway, so the attribute was saying nothing twice.
            :allow "autoplay; encrypted-media; picture-in-picture; fullscreen"
            ;; Belt to the pointer capture's braces, and free: while the drag
            ;; runs there is nothing in the iframe worth clicking anyway.
            :style (when @*drag {:pointer-events "none"})}]]
         ;; `url`, not a watch address rebuilt from `id`: what the item carried
         ;; is what a phone should be sent to.
         (when @*qr-open? [qr-overlay/overlay url #(reset! *qr-open? false)])]))))
