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

   What is kept is the item's id and its file, not a position: a position goes
   stale the moment the list changes, while the id finds the item again wherever
   it moved to. The file is kept as well so an item that has dropped out of the
   list stays on screen instead of the viewer blanking -- it is just no longer
   anywhere to step from."
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
  "Show `file`, the image of the item `id`."
  [id file]
  (reset! *open {:id id :file file}))

(defn close! [] (reset! *open nil))

(defn open? [] (some? @*open))

(defn images
  "The items of `items` that have an image file, in their order."
  [items]
  (filterv (comp image-file? file-of) items))

(defn- step!
  [imgs idx delta]
  (when idx
    (when-let [item (get imgs (+ idx delta))]
      (open! (:id item) (file-of item)))))

(defn- src [file] (str "/imgs/" (js/encodeURIComponent file)))

(defn- overlay
  [_*state]
  (r/create-class
    {;; Focused on mount so the keys arrive here at all -- the same move
     ;; ui.qr-overlay makes, for the same reason: unfocused, ← → and Escape go to
     ;; #main-layer, whose single-key shortcuts would act on the list behind.
     :component-did-mount (fn [_]
                            (when-let [el (.getElementById js/document "image-viewer")] (.focus el)))
     ;; And handed back, or the app is left deaf on <body> once this is gone.
     :component-will-unmount (fn [_]
                               (when-let [el (.getElementById js/document "main-layer")]
                                 (.focus el)))
     :reagent-render
       (fn [*state]
         (let [{:keys [id file]} @*open
               imgs (images (:items @*state))
               idx (first (keep-indexed (fn [i item] (when (= id (:id item)) i)) imgs))
               item (when idx (get imgs idx))
               prev? (and idx (pos? idx))
               next? (and idx (< (inc idx) (count imgs)))
               stop (fn [e] (.stopPropagation e))]
           [:div#image-viewer
            {:tabIndex 0
             :role "dialog"
             :aria-modal "true"
             :aria-label "Image viewer"
             ;; Every key is stopped, not only the ones used here: while this is
             ;; up the app underneath must take no keystrokes at all. Tab is
             ;; held too. The overlay stops the mouse and nothing else (cookbook
             ;; #39), so a Tab let through walks the focus out to controls
             ;; behind it that nobody can see.
             :on-key-down (fn [e]
                            (.stopPropagation e)
                            (case (.-code e)
                              "Escape" (do (.preventDefault e) (close!))
                              "ArrowLeft" (do (.preventDefault e) (step! imgs idx -1))
                              "ArrowRight" (do (.preventDefault e) (step! imgs idx 1))
                              "Tab" (.preventDefault e)
                              nil))
             :on-click (fn [e] (stop e) (close!))}
            [:div.image-viewer-frame
             [:img.image-viewer-image
              ;; Clicking the picture itself does not close it; only around it.
              {:src (src file) :alt (or (not-empty (:title item)) file) :on-click stop}]]
            [:div.image-viewer-bar
             {:on-click stop
              ;; A pressed button would otherwise take the focus, and the one
              ;; at the end of the row turns disabled under it -- which drops
              ;; the focus to <body>, and ← → and Escape with it.
              :on-mouse-down #(.preventDefault %)}
             [:button.image-viewer-prev
              {:type "button"
               :title "Previous image (←)"
               :aria-label "Previous image"
               :disabled (not prev?)
               :on-click (fn [e] (stop e) (step! imgs idx -1))} "‹"]
             [:span.image-viewer-position
              (if idx (str (inc idx) " / " (count imgs)) (str "– / " (count imgs)))]
             [:span.image-viewer-title (or (not-empty (:title item)) file)]
             [:button.image-viewer-next
              {:type "button"
               :title "Next image (→)"
               :aria-label "Next image"
               :disabled (not next?)
               :on-click (fn [e] (stop e) (step! imgs idx 1))} "›"]]
            [:button.image-viewer-close
             {:type "button"
              :on-mouse-down #(.preventDefault %)
              :title "Close (Esc)"
              :aria-label "Close the image viewer"
              :on-click (fn [e] (stop e) (close!))} "✕"]]))}))

(defn component
  "Mounted once, from ui/component."
  [*state]
  (when (open?) [overlay *state]))
