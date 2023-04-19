(ns ui.modals.context-edit
  (:require [reagent.core :as r]
            [net.eighttrigrams.cljs-text-editor.editor :as editor]
            api))

(defn- get-title-el []
  (.getElementById js/document "context-title"))

(defn- get-short-title-el []
  (.getElementById js/document "context-short-title"))

(defn- get-tags-el []
  (.getElementById js/document "context-tags")) ;; TODO maybe just name it "tags"

(defn component [context]
  (r/create-class 
   {:component-did-mount #(do (.focus (get-title-el))
                              (editor/create (get-title-el) {:input-field-mode? true})
                              (editor/create (get-short-title-el) {:input-field-mode? true})
                              (editor/create (get-tags-el) {:input-field-mode? true}))
    :reagent-render
    (fn [context]
      [:<> 
       [:div
        [:input#context-title.line
         {:autoComplete :off
          :defaultValue (:title context)}]]
       [:div
        [:input#context-short-title.line
         {:autoComplete :off
          :defaultValue (:short_title context)}]] ;; TODO work with short-title
       [:div
        [:input#context-tags.line
         {:autoComplete :off
          :defaultValue (:tags context)}]]])}))

(defn get-values [id]
  {:context
   {:id          id
    :title       (.-value (get-title-el))
    :short_title (.-value (get-short-title-el))
    :tags        (.-value (get-tags-el))}
   :secondary-contexts-ids '()})
