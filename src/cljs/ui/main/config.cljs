(ns ui.main.config
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [ui.replica :as replica]
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
               ;; The poll lists are read here but written through the same
               ;; commands, so a replica's refusal arrives on this path too.
               (let [res (replica/refusal-notice! res)]
                 (swap! *state (fn [state]
                                 (merge state
                                        (select-keys res
                                                     [:youtube-poll-channels :atom-poll-feeds])))))))))

(defn- load!
  [*state]
  (refresh! *state (api/list-youtube-poll-channels @*state))
  (refresh! *state (api/list-atom-poll-feeds @*state)))

(defn- add!
  [*state input min-duration]
  (refresh! *state (api/add-youtube-poll-channel @*state input min-duration)))

(defn- update-min!
  [*state id min-duration]
  (refresh! *state (api/update-youtube-poll-channel @*state id min-duration)))

(defn- remove! [*state id] (refresh! *state (api/delete-youtube-poll-channel @*state id)))

(defn- add-feed! [*state input] (refresh! *state (api/add-atom-poll-feed @*state input)))

(defn- remove-feed! [*state id] (refresh! *state (api/delete-atom-poll-feed @*state id)))

(defn- close!
  [*state]
  (swap! *state #(dissoc % :config-page? :youtube-poll-channels :atom-poll-feeds)))

(defn- youtube-tab
  [*state *input *min]
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
         [:button.config-channel-delete {:on-click #(remove! *state id)} "delete"]]))]])

(defn- atom-tab
  [*state *feed-input]
  [:div.config-body
   [:form.config-add
    {:on-submit (fn [e]
                  (.preventDefault e)
                  (let [v (str/trim @*feed-input)]
                    (when (seq v)
                      (add-feed! *state v)
                      (reset! *feed-input ""))))}
    [:input {:type "text"
             :placeholder "Atom feed URL"
             :value @*feed-input
             :on-change #(reset! *feed-input (.. % -target -value))}]
    [:button {:type "submit"} "Add"]]
   [:ul.config-channels
    (doall
      (for [{:keys [id feed-url name]} (:atom-poll-feeds @*state)]
        ^{:key id}
        [:li.config-channel
         [:span.config-channel-name (or name feed-url)]
         [:span.config-channel-id feed-url]
         [:button.config-channel-delete {:on-click #(remove-feed! *state id)} "delete"]]))]])

(defn component
  [*state]
  (r/with-let [*input (r/atom "")
               *min (r/atom "")
               *feed-input (r/atom "")
               *tab (r/atom :youtube)
               _ (load! *state)]
    [:div#config-page
     [:div.config-header
      [:button.config-close {:on-click #(close! *state) :title "Close"} "✕"]
      [:div.config-tabs
       [:button.config-tab
        {:class (when (= :youtube @*tab) "active") :on-click #(reset! *tab :youtube)}
        "YouTube polling"]
       [:button.config-tab
        {:class (when (= :atom @*tab) "active") :on-click #(reset! *tab :atom)}
        "Atom feeds"]]]
     (case @*tab
       :youtube [youtube-tab *state *input *min]
       :atom [atom-tab *state *feed-input])]))
