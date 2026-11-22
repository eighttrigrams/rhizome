(ns ui.main.context-badges
  (:require [ui.actions :as actions]
            [ui.image-viewer :as image-viewer]))

(defn- open-file!
  "The green badge. Every file opens in the app's own viewer, which shows what
   the browser can show and offers the rest to download. Option+click still
   sends it out to the server machine's system viewer (/open/), the way every
   click used to -- kept for now, while the server is the Mac in front of the
   owner."
  [e {:keys [file] :as link}]
  (.stopPropagation e)
  (if-not (.-altKey e)
    (image-viewer/open! link)
    ;; encodeURIComponent, not encodeURI: a # or a ? in the name is otherwise
    ;; taken for the start of a fragment or a query, and the server is asked
    ;; for the name cut short there. Compojure decodes the route param.
    (js/fetch (str "/open/" (js/encodeURIComponent file)))))

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
