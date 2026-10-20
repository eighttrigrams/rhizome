(ns ui.image-viewer
  "An item's image file, shown over nearly the whole window, with a way to step
   to the previous and next image in the list.

   The green badge used to hand every file to the Mac's own viewer (/open/...).
   For an image it now opens here; Option+click still goes out, and every other
   kind of file goes out as before -- there is nothing in the app to show a pdf
   or an mp4 in. See ui.main.context-badges.

   ## Which images, in which order

   The right-hand list, `(:items @*state)`, as it is on screen: it already is
   the context with its secondary contexts, the filters, the search and the
   hierarchy mode applied, which is exactly \"the selection as we see it\". The
   viewer walks it in order and skips whatever has no image file. It is read on
   every render rather than copied when the viewer opens, so a list that changes
   underneath is the list that is stepped through.

   An image counts by its file's suffix, the same four that
   et.rz.hub.repository.homefolder files under :images -- which is also what
   makes /imgs/<file> the address it is served at. tiff goes to :docs there, and
   a browser cannot draw it anyway, so it is not an image here either.

   ## Why what is open lives in this namespace, not in *state

   The floating player's docstring names the hazard: every dispatch response
   reset!s *state from a snapshot taken when the request went out
   (ui.actions.common/fetch-and-reset!). An image opened, or closed, while a
   search was in flight would be undone by its answer -- the viewer vanishing
   under the owner, or coming back after Escape. Nothing else in the app has to
   know an image is up, so it need not be shared, and the one component that
   draws it is mounted once for the life of the page, from ui/component. The
   badge that opens it is not that component, though, so the atom is the
   namespace's rather than the component's own; defonce, so a hot reload does
   not drop it.

   What is kept is the item's id, its place in the list and its file. The place
   because an id is not enough to say which card was meant: hierarchy mode lists
   a node once per path, so one item can stand twice in one list, and found by
   id alone the second copy was the first again -- Next from it jumped back, and
   whatever came after it could not be reached. The id because the place goes
   stale when the list changes: if the item is no longer at its place, the
   viewer falls back to wherever the id first is. The file and the title so an
   item that is not in the list at all -- the green badge is on the left-hand
   cards too, whose item is usually not among the ones listed on the right --
   is still shown, alone, with nothing offered to step to.

   ## Where the focus may go while it is up

   Only into the viewer and into the floating player, which sits above it on
   purpose and whose controls, and QR overlay, are there to be used. Anything
   else that takes the focus is overruled. The case that needs it: the player's
   QR overlay hands the focus to #main-layer when it closes -- right when no
   viewer is up, and with one up it would put the hidden app's single-key
   shortcuts under the keyboard.

   And the viewer's own keys follow it where the focus goes outside it. Into
   the player, a click on one of its buttons leaves the focus on that button,
   where ← → and Escape mean nothing and never reach the viewer; and its X
   takes itself away and leaves the focus on <body>, where no focusin fires
   and nothing listens. Either way the viewer's keys were dead until it was
   clicked. So while it is up the viewer also takes ← → and Escape that come
   from the player, and every key that arrives at <body>, and the focus back
   with them. Other keys on a player button, Enter and Space, stay the
   button's. Not by keeping the player's controls from taking the focus: they
   are the player's, and its drag depends on how the pointer is held.

   Both listeners are made per mount and removed as the same functions. Put on
   by name, a hot reload in between would have the unmount remove the new
   definition and leave the old one on document for good."
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(defonce ^:private *open (r/atom nil))

(def ^:private image-suffixes #{"jpg" "jpeg" "png" "webp"})

(defn image-file?
  [file]
  (boolean (when (string? file)
             (when-let [idx (str/last-index-of file ".")]
               (image-suffixes (str/lower-case (subs file (inc idx))))))))

(defn- file-of [item] (:file (:resource-links (:data item))))

(defn open!
  "Show `file`, the image of the item `id` called `title`, which stands at
   `pos` in the list -- nil for a card that is not one of the list's."
  [{:keys [id file title pos]}]
  (reset! *open {:id id :file file :title title :pos pos}))

(defn close! [] (reset! *open nil))

(defn open? [] (some? @*open))

(defn images
  "The items of `items` that have an image file, in their order, each with
   the place it stands at in `items`."
  [items]
  (into [] (keep-indexed (fn [pos item] (when (image-file? (file-of item)) {:pos pos :item item})))
        items))

(defn- index-of
  "Where in `imgs` the open image is: at its own place if the list still has
   the item there, else wherever the item first is, else nowhere."
  [imgs {:keys [id pos]}]
  (let [at (fn [pred] (first (keep-indexed (fn [i img] (when (pred img) i)) imgs)))]
    (or (when pos (at #(and (= pos (:pos %)) (= id (:id (:item %))))))
        (at #(= id (:id (:item %)))))))

(defn- step!
  [imgs idx delta]
  (when idx
    (when-let [{:keys [pos item]} (get imgs (+ idx delta))]
      (open! {:id (:id item) :file (file-of item) :title (:title item) :pos pos}))))

(def ^:private focus-may-be-in
  "The viewer itself, the floating player above it, and the player's QR overlay."
  "#image-viewer, #floating-player, #qr-overlay")

(defn- keep-focus!
  [e]
  (let [target (.-target e)]
    (when-not (and (instance? js/Element target) (.closest target focus-may-be-in))
      (focus-viewer!))))

(defn- handle-key!
  "The viewer's keys, against the list as it is now. Every key is stopped, not
   only the ones used here: while the viewer is up the app underneath must take
   no keystrokes at all. Tab is held too. The overlay stops the mouse and
   nothing else (cookbook #39), so a Tab let through walks the focus out to
   controls behind it that nobody can see."
  [*state e]
  (.stopPropagation e)
  (let [imgs (images (:items @*state))
        idx (index-of imgs @*open)]
    (case (.-code e)
      "Escape" (do (.preventDefault e) (close!))
      "ArrowLeft" (do (.preventDefault e) (step! imgs idx -1))
      "ArrowRight" (do (.preventDefault e) (step! imgs idx 1))
      "Tab" (.preventDefault e)
      nil)))

(defn- focus-viewer! []
  (when-let [el (.getElementById js/document "image-viewer")] (.focus el)))

(defn- src [file] (str "/imgs/" (js/encodeURIComponent file)))

(defn- overlay
  [*state]
  (let [;; This mount's own two, so that what is removed is what was added.
        on-focusin (fn [e] (keep-focus! e))
        on-stray-key (fn [e]
                       (let [target (.-target e)]
                         (when (or (identical? target (.-body js/document))
                                   (and (instance? js/Element target)
                                        (.closest target "#floating-player")
                                        (#{"Escape" "ArrowLeft" "ArrowRight"} (.-code e))))
                           (focus-viewer!)
                           (handle-key! *state e))))]
    (r/create-class
      {;; Focused on mount so the keys arrive here at all -- the same move
       ;; ui.qr-overlay makes, for the same reason: unfocused, ← → and Escape go to
       ;; #main-layer, whose single-key shortcuts would act on the list behind.
       :component-did-mount (fn [_]
                              (.addEventListener js/document "focusin" on-focusin)
                              (.addEventListener js/document "keydown" on-stray-key)
                              (focus-viewer!))
       ;; And handed back, or the app is left deaf on <body> once this is gone --
       ;; after the guards are down, or the focus one would take it straight back.
       :component-will-unmount (fn [_]
                                 (.removeEventListener js/document "focusin" on-focusin)
                                 (.removeEventListener js/document "keydown" on-stray-key)
                                 (when-let [el (.getElementById js/document "main-layer")]
                                   (.focus el)))
       :reagent-render
         (fn [*state]
           (let [{:keys [file title] :as open} @*open
                 imgs (images (:items @*state))
                 idx (index-of imgs open)
                 title (or (not-empty (:title (:item (get imgs idx)))) (not-empty title) file)
                 prev? (and idx (pos? idx))
                 next? (and idx (< (inc idx) (count imgs)))
                 stop (fn [e] (.stopPropagation e))]
             [:div#image-viewer
              {:tabIndex 0
               :role "dialog"
               :aria-modal "true"
               :aria-label "Image viewer"
               :on-key-down #(handle-key! *state %)
               :on-click (fn [e] (stop e) (close!))}
              [:div.image-viewer-frame
               [:img.image-viewer-image
                ;; Clicking the picture itself does not close it; only around it.
                {:src (src file) :alt title :on-click stop}]]
              [:div.image-viewer-bar
               {:on-click stop
                ;; A pressed button would otherwise take the focus, and the one
                ;; at the end of the row turns disabled under it -- which drops
                ;; the focus to <body>, and ← → and Escape with it.
                :on-mouse-down #(.preventDefault %)}
               ;; Nothing to step with for an image that is not in the list: a
               ;; position of "- / n" and two dead buttons would only say so.
               (when idx
                 [:button.image-viewer-prev
                  {:type "button"
                   :title "Previous image (←)"
                   :aria-label "Previous image"
                   :disabled (not prev?)
                   :on-click (fn [e] (stop e) (step! imgs idx -1))} "‹"])
               (when idx [:span.image-viewer-position (str (inc idx) " / " (count imgs))])
               [:span.image-viewer-title title]
               (when idx
                 [:button.image-viewer-next
                  {:type "button"
                   :title "Next image (→)"
                   :aria-label "Next image"
                   :disabled (not next?)
                   :on-click (fn [e] (stop e) (step! imgs idx 1))} "›"])]
              [:button.image-viewer-close
               {:type "button"
                :on-mouse-down #(.preventDefault %)
                :title "Close (Esc)"
                :aria-label "Close the image viewer"
                :on-click (fn [e] (stop e) (close!))} "✕"]]))})))

(defn component
  "Mounted once, from ui/component."
  [*state]
  (when (open?) [overlay *state]))
