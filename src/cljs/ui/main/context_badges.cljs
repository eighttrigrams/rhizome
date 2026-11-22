(ns ui.main.context-badges
  (:require [ui.actions :as actions]
            [ui.image-viewer :as image-viewer]))

(defn- download!
  "Have the browser download `file`: a link with the download attribute,
   clicked and dropped. The server's Content-Disposition names it."
  [file]
  (let [a (.createElement js/document "a")]
    (set! (.-href a) (image-viewer/download-href file))
    (set! (.-download a) "")
    (.appendChild (.-body js/document) a)
    (.click a)
    (.remove a)))

(defn- open-file!
  "The green badge. Every file opens in the app's own viewer, which shows what
   the browser can show and offers the rest to download. Option+click
   downloads it, whatever its kind -- the owner's \"option+click becomes
   download\". It used to send the file to the server machine's own viewer
   (/open/, macOS `open`), which only ever worked with the browser sitting at
   the server's Mac."
  [e {:keys [file] :as link}]
  (.stopPropagation e)
  (if (.-altKey e)
    (download! file)
    (image-viewer/open! link)))

(defn component
  [*state contexts]
  [:span.contexts
   (doall
     (map (fn [[idx {:keys [title date file number context show-badge? is-context?] :as badge}]]
            (case idx
              :file [:span.badge.light
                     {:key idx
                      :on-click #(open-file! % badge)}
                     "🟢"]
              0 [:span.badge.light
                 {:key idx
                  :on-click (fn [e] (.stopPropagation e) (let [callback-fn context] (callback-fn)))}
                 "⭕"]
              :date [:span.badge.light {:key :date} date]
              :number [:span.badge.light {:key :number} number]
              (when (and is-context? show-badge?)
                [:span.badge
                 {:key idx
                  :on-click (fn [e]
                              (.stopPropagation e)
                              (when-not (or (:link-context @*state) (:link-item @*state))
                                (actions/select-context! *state {:id idx})))} title])))
       contexts))])
