(ns ui.main.config
  (:require [clojure.string :as str]
            [reagent.core :as r]
            api))

(defn- refresh!
  [*state p]
  (-> p
      (.then (fn [res]
               (swap! *state assoc :youtube-poll-channels (:youtube-poll-channels res))))))

(defn- load! [*state] (refresh! *state (api/list-youtube-poll-channels @*state)))

(defn- add! [*state input] (refresh! *state (api/add-youtube-poll-channel @*state input)))

(defn- remove! [*state id] (refresh! *state (api/delete-youtube-poll-channel @*state id)))

(defn- close! [*state] (swap! *state #(dissoc % :config-page? :youtube-poll-channels)))

(defn component
  [*state]
  (r/with-let [*input (r/atom "")
               _ (load! *state)]
    [:div#config-page
     [:div.config-header
      [:button.config-close {:on-click #(close! *state) :title "Close"} "✕"]
      [:h2 "YouTube polling"]]
     [:div.config-body
      [:form.config-add
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (let [v (str/trim @*input)]
                       (when (seq v)
                         (add! *state v)
                         (reset! *input ""))))}
       [:input {:type "text"
                :placeholder "Channel id (UC…), channel URL, or video URL"
                :value @*input
                :on-change #(reset! *input (.. % -target -value))}]
       [:button {:type "submit"} "Add"]]
      [:ul.config-channels
       (doall
         (for [{:keys [id channel-id name]} (:youtube-poll-channels @*state)]
           ^{:key id}
           [:li.config-channel
            [:span.config-channel-name (or name channel-id)]
            [:span.config-channel-id channel-id]
            [:button.config-channel-delete {:on-click #(remove! *state id)} "delete"]]))]]]))
