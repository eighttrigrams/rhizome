(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [cambium.core :as log]
            [cognitect.transit :as transit]
            [config :as config]
            [replica :as replica]
            [repository :refer
             [list-resources insert-item insert-context change-secondary-contexts-selection
              change-secondary-contexts-unassigned-selected change-secondary-contexts-inverted
              change-description-filter
              deselect-secondary-contexts finish-linking-item reprioritize-item cycle-search-mode
              store-current-view load-stored-context remove-stored-context update-item unlink-item
              unlink-selected-item-from-container delete-item upgrade-item-to-context
              link-selected-context-to-context select-last-context fetch-context deselect-context
              fetch-aggregated-contexts delete-context fetch-item-description edit-item-in-obsidian
              fetch-item-provenance
              sync-obsidian-changes discard-obsidian-changes get-obsidian-file-content update-annotations
              fetch-relation-description
              vector-search-related-items vector-threshold-search-related-items]]
            [poll :refer
             [list-youtube-poll-channels add-youtube-poll-channel delete-youtube-poll-channel
              update-youtube-poll-channel
              list-atom-poll-feeds add-atom-poll-feed delete-atom-poll-feed]]))

(defn- handle-error [e] (log/error {:error-handler :handle-error} e "an error occured"))

(defdispatch handler*
             {:error-handler handle-error :pass-server-args? true}
             list-resources
             insert-item
             insert-context
             change-secondary-contexts-selection
             change-secondary-contexts-unassigned-selected
             change-secondary-contexts-inverted
             change-description-filter
             deselect-secondary-contexts
             finish-linking-item
             reprioritize-item
             cycle-search-mode
             store-current-view
             load-stored-context
             remove-stored-context
             delete-item
             unlink-item
             unlink-selected-item-from-container
             update-item
             fetch-aggregated-contexts
             select-last-context
             upgrade-item-to-context
             link-selected-context-to-context
             fetch-context
             deselect-context
             delete-context
             fetch-item-description
             fetch-item-provenance
             edit-item-in-obsidian
             sync-obsidian-changes
             discard-obsidian-changes
             get-obsidian-file-content
             update-annotations
             fetch-relation-description
             vector-search-related-items
             vector-threshold-search-related-items
             list-youtube-poll-channels
             add-youtube-poll-channel
             delete-youtube-poll-channel
             update-youtube-poll-channel
             list-atom-poll-feeds
             add-atom-poll-feed
             delete-atom-poll-feed)

;; --- read-only replica guard ------------------------------------------------

(def ^:private read-only-commands
  "The commands above that cannot write the db. Everything else counts as a
   write, so a command added to the dispatch list without being classified here
   is refused on a replica rather than let through -- the guard fails closed.

   Three entries need a word:
   - `list-resources` is both: a search on its own, but one of its :cmd branches
     saves a description (see writing-list-resources-cmds).
   - `fetch-context` is a read that touches the row's ordering timestamps; on a
     replica the touch is skipped (see repository/fetch-context) so that opening
     a context keeps working.
   - `discard-obsidian-changes` only deletes a temp file. `edit-item-in-obsidian`
     is deliberately NOT here although it writes no db row either: it is the
     entry point of a write flow, and refusing it up front beats stranding the
     human's edit in a temp file that sync-obsidian-changes then refuses."
  #{"list-resources"
    "fetch-aggregated-contexts"
    "fetch-context"
    "deselect-context"
    "select-last-context"
    "fetch-item-description"
    "fetch-item-provenance"
    "fetch-relation-description"
    "get-obsidian-file-content"
    "discard-obsidian-changes"
    "vector-search-related-items"
    "vector-threshold-search-related-items"
    "list-youtube-poll-channels"
    "list-atom-poll-feeds"})

(def ^:private writing-list-resources-cmds
  "The :cmd values that make a list-resources call a write."
  #{:update-context-description})

(defn- read-args
  "Decode the transit-encoded args. Only list-resources needs this to be
   classified; nil (unparseable args) makes the classification fall back to
   treating the call as a write."
  [args]
  (try (-> (java.io.ByteArrayInputStream. (.getBytes ^String args "UTF-8"))
           (transit/reader :json)
           transit/read)
       (catch Exception _ nil)))

(defn- write-command?
  [fn-name args]
  (cond
    (not (contains? read-only-commands fn-name)) true
    (= "list-resources" fn-name) (let [decoded (read-args args)]
                                   (or (nil? decoded)
                                       (boolean (some-> decoded
                                                        first
                                                        :cmd
                                                        writing-list-resources-cmds))))
    :else false))

(defn- refusal
  "A refusal in the envelope the SPA already reads, carrying :read-only-refused
   for the UI to surface (see ui.replica). Answering in-band keeps the response
   a normal one: the list the user is looking at stays on screen.

   It clears :cmd and :arg exactly as a successful call does (see
   repository/list-resources, which merges {:cmd nil :arg nil} over every
   result). The SPA merges the response over the state it sent and reset!s its
   atom from that, so without the clear a refused description save would leave
   :cmd :update-context-description latched in state -- and every later feed or
   search request would re-send that write cmd and be refused in turn."
  []
  (let [os (java.io.ByteArrayOutputStream. 512)]
    (transit/write (transit/writer os :json)
                   {:read-only-refused replica/message :cmd nil :arg nil})
    {:return (.toString os "UTF-8") :thrown nil}))

(defn handler
  "The /ui entry point. The SPA carries queries AND mutations through this one
   POST, so a read-only replica cannot refuse by HTTP method without breaking
   reading: it refuses per command instead, here at the dispatch level. Query
   commands pass through untouched -- see read-only-commands for the
   classification, and `replica` for the rest of the guards."
  [{{fn-name :fn args :args} :body :as req}]
  (if (and (replica/read-only?) (write-command? fn-name args))
    ;; INFO, not WARN like the /api refusal: on a replica plain browsing keys are
    ;; write commands (s -> cycle-search-mode, the secondary-context badges ->
    ;; change-secondary-contexts-*), so a refusal here is ordinary operation
    ;; rather than the anomaly an /api write attempt is.
    (do (log/info {:event "replica-refusal" :uri "/ui" :fn fn-name}
                  (str "read-only replica: refused /ui command " fn-name))
        (refusal))
    (handler* req)))
