(ns ui.main.provenance
  "Who wrote each line of the description that is standing now.

   Not the version list in another shape. The bar over the description says
   where each whole *version* came from; this says, of the text as it reads at
   this moment, which stretches are the owner's own hand and which an agent may
   rewrite. An item he wrote once and an agent has edited nineteen times since
   still has his opening paragraph at 1.00 here, and nothing in a list of
   nineteen agent versions would tell him that.

   `view` is the answer without the page around it, so the same one can be read of
   a relation's text inside the modal that edits it -- see ui.modals.annotation-edit."
  (:require [clojure.string :as str]))

;; The two ends of the spectrum, as solid colours. Mixed in OKLCH and never in
;; oklab: oklab interpolates straight through the middle of the colour solid, so
;; two opposed hues meet at a desaturated grey and the value that most needs to
;; look like something -- a stretch both of them have worked on -- would come
;; out looking like nothing at all. OKLCH carries the mix around the hue circle
;; instead and keeps the chroma up, so the middle is its own visible colour.
;;
;; Blue and warm red rather than red and green: the middle of these two is a
;; vivid magenta, and neither end disappears for a red-green colour-blind
;; reader. Colour is never the only channel here anyway -- the key below is
;; labelled in words, the number is printed at the head of every range, and the
;; legend the server sends says the whole thing in a sentence.
(def ^:private sacred-colour
  "1.00 -- his."
  "oklch(0.62 0.15 250)")

(def ^:private free-colour
  "0.00 -- theirs."
  "oklch(0.65 0.16 30)")

(defn- caution-colour
  "The colour for `caution`, at `alpha`.

   Two solid colours mixed, and then ONE alpha applied to what comes out. Not
   two pre-faded washes mixed together: fading first and mixing second lets the
   two transparencies compose, and the middle of the scale comes out weaker than
   both of its ends -- again dimmest exactly where the answer is most worth
   seeing."
  [caution alpha]
  (str "color-mix(in oklch, "
       "color-mix(in oklch, " sacred-colour " " (* 100.0 caution) "%, " free-colour ") "
       (* 100.0 alpha) "%, transparent)"))

(defn- close!
  [*state]
  (swap! *state dissoc :provenance-page? :provenance))

(defn- source-lines
  "The description split the way the server counted it.

   `#\"\\n\" -1` and NOT `str/split-lines`, which drops trailing empty strings.
   Most bodies end in a newline, so the ranges routinely say n+1 where
   split-lines says n, and the whole view would simply be one row short at the
   bottom -- no error, no gap, every other row correct and correctly tinted.
   The limit of -1 is what disables the discard in ClojureScript as well as in
   Clojure."
  [description]
  (str/split (or description "") #"\n" -1))

(defn- caution-by-line
  "{line-number -> caution} over the lines the ranges actually cover.

   A line that is in no range is absent here and is drawn untinted. That is not
   the same as drawing it at either end: an uncoloured row says nothing, and
   saying nothing is right when nothing is known. Defaulting it to the free end
   would invite a rewrite of a line whose author was never established, and
   defaulting it to the sacred end would fence off text nobody claimed."
  [ranges]
  (into {}
        (for [{:keys [from to caution]} ranges
              n (range from (inc to))]
          [n caution])))

(defn- range-heads
  "{line-number -> caution} for the first line of each range only.

   The number is printed once where a range starts rather than on all of its
   lines: a range is the unit the answer comes in, and a column of the same
   figure repeated forty times reads as forty separate findings."
  [ranges]
  (into {} (map (juxt :from :caution)) ranges))

(defn- colour-key
  "The spectrum, built out of the same function that colours the rows, so the
   key cannot drift from what it is a key to. Discrete stops rather than a CSS
   gradient for the same reason -- a gradient would interpolate by its own rule
   and the two would agree only by luck."
  []
  [:div.provenance-key
   [:span.provenance-key-end "0.00 · theirs, free to edit"]
   [:div.provenance-key-bar
    (for [i (range 21)]
      ^{:key i} [:div.provenance-key-stop {:style {:background-color (caution-colour (/ i 20.0) 1.0)}}])]
   [:span.provenance-key-end "his, leave alone · 1.00"]])

(defn- line-row
  [n text caution head?]
  [:div.provenance-line {:data-caution (when caution (.toFixed caution 2))
                         :style (when caution
                                  {:background-color (caution-colour caution 0.22)})}
   [:div.provenance-bar
    {:style (when caution {:background-color (caution-colour caution 1.0)})}]
   [:div.provenance-lineno n]
   [:div.provenance-caution (when head? (.toFixed caution 2))]
   ;; A zero-width space so an empty source line still gives the row a height
   ;; and its tint something to sit behind. A blank line is text too, and a
   ;; blank line of his is as much his as any other.
   [:div.provenance-text (if (str/blank? text) "\u200b" text)]])

(defn view
  "The answer itself: the legend, the key, and the text with a tint per line.

   Everything except the header, and that is what makes it reusable -- the same
   view stands on the item's own page and inside the relation modal, which is
   where a relation's is read (ui.modals.annotation-edit). Two renderings of one
   number is how two surfaces come to disagree about it, and the wording is the
   server's in both places for the same reason.

   `provenance` is `{:description :caution}`, and its three states are three
   different things: nil is the fetch still in flight, a nil `:caution` is a text
   there is nothing to attribute in, and anything else is the answer. The sentence
   for the middle one is the caller's, because only the caller knows what kind of
   thing was empty."
  ([provenance] (view provenance "This item has no description to attribute."))
  ([provenance nothing-to-attribute]
   (let [{:keys [description caution]} provenance
         {:keys [legend ranges]} caution
         lines (source-lines description)
         by-line (caution-by-line ranges)
         heads (range-heads ranges)]
     (cond
       (nil? provenance) [:p.provenance-empty "Reading the history…"]
       (nil? caution) [:p.provenance-empty nothing-to-attribute]
       :else
       [:<>
         ;; The server's own sentence, rendered rather than retyped. A second
         ;; wording here is how two surfaces come to explain one number
         ;; differently, and the agent reading the API and the person reading
         ;; this page have to be told the same thing.
         [:p.provenance-legend legend]
         [colour-key]
         ;; The source text, not the rendered markdown. The ranges index source
         ;; lines and rendering does not preserve them -- a paragraph is many
         ;; source lines inside one <p>, so tinting rendered blocks would mean
         ;; guessing which block a line landed in. It would be wrong exactly
         ;; where it matters most: a paragraph half his and half an agent's
         ;; would have to pick one colour, and would then be saying something
         ;; false about his own text.
         [:div.provenance-lines
          (map-indexed (fn [i text]
                         (let [n (inc i)]
                           ^{:key n} [line-row n text (by-line n) (contains? heads n)]))
                       lines)]]))))

(defn component
  [*state]
  (let [{:keys [selected-item provenance]} @*state]
    [:div#provenance-page
     [:div.config-header
      [:button.config-close {:on-click #(close! *state) :title "Close"} "✕"]
      [:h2 "Provenance"]
      [:span.provenance-subject
       (str "#" (:id selected-item)
            (when-let [title (:title selected-item)] (str " · " title)))]]
     [view provenance]]))
