(ns provenance
  "How careful an agent should be in a text this system holds, line by line — an
  item's description, or the text one relation carries.

  An adapter and nothing else. The question itself belongs to `et.uvt.caution`,
  and `assess` there is the specification of what the numbers mean — islands,
  absorption, why a stretch both sides have touched sits in the middle rather
  than falling to one end. None of that arithmetic is repeated here, and none of
  it should be: this namespace's whole job is to hand that function rhizome's
  history in the shape it asks for, and to say which of rhizome's source markers
  count as the owner's.

  Two of those translations are the entire content of the file, and both are the
  kind of thing that goes wrong quietly rather than loudly — see `of-versions`
  for the ordering and `source-of` for the empty column."
  (:require [clojure.string :as str]
            [et.uvt.caution :as uvt]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]))

(def ours
  "The source markers that are the owner's own hand.

  A description saved from the web UI is written by the person sitting in front
  of it, so `\"app\"` is us. `\"api\"` is how an agent writes — every mutation
  through the REST API is stamped with it — and `\"scraper\"` is how a feed
  writes; both are them.

  **`\"obsidian\"` is us as well**, and it is here on the same reasoning rather
  than by enumeration. `repository/sync-from-obsidian` stamps a description that
  came back from the owner's own editor, so those lines are as much his hand as
  anything typed into the web UI — the marker records which door the text came
  through, not who wrote it. Left out, an edit he made in Obsidian would come
  back at 0.00 and an agent would be told in so many words that it may rewrite
  it, which is the same failure the empty-column rule in `source-of` exists to
  avoid. A marker nobody has seen before falls the other way, to *them*, and
  that is the right default for a new writer: an unknown door is more likely to
  be a new machine than a new hand.

  This is the one place rhizome takes sides. `et.uvt.core` is deliberately blind
  to what a marker means and only ever asks whether two are equal, which is what
  lets it be handed a vocabulary it has never heard of; `et.uvt.caution` asks for
  the side to be named once, from outside, and this is that naming. Nothing else
  in rhizome needs to have an opinion about it."
  #{"app" "obsidian"})

(def legend
  "What a caution number means, in words, for whoever reads one.

  Handed out with the ranges on every read that carries them, and that is not
  redundancy. The reader is an agent that may have fetched one item and read
  nothing else in this codebase; to it a bare `0.0` beside a line range is a
  number it would have to already know how to read, and it does not. So the
  scale travels with the answer.

  In rhizome's own vocabulary — its markers are `app`, `api` and `scraper`, and
  an agent holding this string is not in a position to translate someone else's."
  (str "caution runs from 1.00 to 0.00 over the lines of the text it is served with. "
       "1.00 is a stretch written wholly by the owner's own hand — saved from the "
       "web UI (source \"app\") or synced back from his editor (source "
       "\"obsidian\") — and is not yours to rewrite. "
       "0.00 is a stretch written wholly through the REST API (source \"api\") or "
       "by a scraper (source \"scraper\") — free to edit. "
       "In between, both have worked on the stretch and the number is the share of "
       "its lines that are his, so anything above 0.00 still has a line of his in it."))

(defn- source-of
  "The marker to attribute a version by, given what its row says.

  A row with no source at all is read as **the owner's**. Those rows predate the
  column — `description_source` and `history.source` are both added by
  `ensure-column!` in `datastore.schema`, so every description written before
  that migration has nothing in it — and the two ways of reading them are not
  equally wrong. Read as an agent's, his oldest hand-written descriptions come
  back at 0.00 and an agent is told in so many words that they are free to
  rewrite. Read as his, at worst an old scraper import is treated more carefully
  than it deserves and nothing is lost but an edit an agent could have made
  unasked. Only one of those two mistakes damages anything, so the empty column
  falls on the other side.

  Blank counts as absent, not as a marker of its own: an empty string in that
  column says exactly what a null does."
  [source]
  (if (str/blank? source) "app" source))

(defn of-versions
  "`{:legend :ranges}` for a description's version history, or nil when there is
  no description to be careful in.

  **`versions` arrives newest first** — that is what `ds/get-description-history`
  returns, current version at the head, and `uvt/assess` wants the opposite. So
  it is reversed here, and the reversal is the one line in this namespace worth
  reading twice. Handing the list over in the order it arrives does not throw and
  does not come back malformed: the fold simply replays the history backwards,
  and every line is confidently attributed to whoever wrote the version *after*
  it. The last agent to touch an item would own the owner's opening paragraph,
  the answer would look entirely well-formed, and nothing but a test would say
  otherwise. `of-versions-reverses-the-history-test` is that test.

  **The text is the description, not the title.** `get-description-history`
  carries both, and a title is one line — there is nothing to be careful *within*
  a line, and a range of `1–1` says nothing an agent could act on. Caution is a
  statement about where inside a text the boundaries are, so it is asked of the
  only field that has an inside.

  Nil when the newest version has no description: an item may have a history
  because its *title* changed, or have had its description emptied, and in both
  cases there is a version list and no text under it. Ranges over a description
  that is not there would be an answer to a question nobody asked."
  [versions]
  (when (and (seq versions) (not (str/blank? (:text (first versions)))))
    {:legend legend
     :ranges (uvt/assess (mapv (fn [{:keys [text source]}]
                                 {:text (or text "") :source (source-of source)})
                               (reverse versions))
                         {:ours ours})}))

(defn of-item
  "`{:legend :ranges}` for the item's current description, or nil when it has
  none. Ranges are one-based and inclusive, in the line numbering an editor and
  an agent already share.

  **It is not free, and it is on the single-item read path.** The alignment
  underneath is an LCS table per version pair, quadratic in the line count and
  linear in the depth of the history, so the cost is roughly lines² × versions.
  Measured here: the longest description in the owner's database (51k
  characters, one history row) takes ~36 ms, and everything else is under 5.
  Synthetically it turns: 1000 lines over 10 versions is ~1.3 s and 2000 over 10
  is ~4.7 s. Nothing in the database is near that today, and nothing here caps
  it -- a cap would mean answering some items with no ranges and no way for the
  caller to tell that apart from an item with nothing to be careful in, which is
  worse than being slow. If descriptions start growing that long, cache it
  against the item's `updated_at_ctx` rather than truncating the answer."
  [db id]
  (of-versions (:versions (ds/get-description-history db {:id id}))))

(defn of-relation
  "`{:legend :ranges}` for the text one relation is carrying, or nil when it is
   carrying none. Same question as `of-item`, asked of an edge.

   The same `of-versions` and therefore the same reversal, the same legend and the
   same reading of an empty source column -- which is what makes the two answers
   comparable. A relation's history is versioned by exactly the mechanism an
   item's description is (et.vp.ds.relations/get-relation-description-history), so
   there is nothing for this namespace to know about edges beyond where to ask.

   Cheaper than `of-item` in practice for the same reason it is on a colder path:
   the assessment costs lines² × versions, and a relation's text is a paragraph
   about why one thing is filed under another, not a note. It is still asked for
   only when the reader presses the button -- see repository/fetch-relation-provenance."
  [db item-id container-id]
  (of-versions (:versions (relations/get-relation-description-history db
                                                                     item-id
                                                                     container-id))))
