(ns ui.main.config
  (:require [clojure.string :as str]
            [reagent.core :as r]
            api))

(defn- parse-min
  [s]
  (let [s (str/trim (str s))]
    (when (seq s)
      (let [n (js/parseInt s 10)]
        (when-not (js/isNaN n) n)))))

(defn- refresh!
  [*state p]
  (-> p
      (.then (fn [res]
               (swap! *state assoc :youtube-poll-channels (:youtube-poll-channels res))))))

(defn- load! [*state] (refresh! *state (api/list-youtube-poll-channels @*state)))

(defn- add!
  [*state input min-duration]
  (refresh! *state (api/add-youtube-poll-channel @*state input min-duration)))

(defn- update-min!
  [*state id min-duration]
  (refresh! *state (api/update-youtube-poll-channel @*state id min-duration)))

(defn- remove! [*state id] (refresh! *state (api/delete-youtube-poll-channel @*state id)))

(defn- close! [*state] (swap! *state #(dissoc % :config-page? :youtube-poll-channels)))

(defn component
  [*state]
  (r/with-let [*input (r/atom "")
               *min (r/atom "")
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
                         (add! *state v (parse-min @*min))
                         (reset! *input "")
                         (reset! *min ""))))}
       [:input {:type "text"
                :placeholder "Channel id (UC…), channel URL, or video URL"
                :value @*input
                :on-change #(reset! *input (.. % -target -value))}]
       [:input.config-min-input
        {:type "number"
         :min "0"
         :placeholder "min min"
         :value @*min
         :on-change #(reset! *min (.. % -target -value))}]
       [:button {:type "submit"} "Add"]]
      [:ul.config-channels
       (doall
         (for [{:keys [id channel-id name min-duration]} (:youtube-poll-channels @*state)]
           ^{:key id}
           [:li.config-channel
            [:span.config-channel-name (or name channel-id)]
            [:span.config-channel-id channel-id]
            [:label.config-channel-min "min minutes:"
             [:input.config-min-input
              {:type "number"
               :min "0"
               :default-value min-duration
               :on-blur #(update-min! *state id (parse-min (.. % -target -value)))}]]
            [:button.config-channel-delete {:on-click #(remove! *state id)} "delete"]]))]]]))
