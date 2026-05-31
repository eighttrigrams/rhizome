(ns opener
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cambium.core :as log]
            [repository.homefolder :as home]))

(defn open
  [file-id]
  (when-let [path (home/get-target file-id)]
    (when (.exists (io/file path)) (sh/sh "open" path))))

(defn- open-in-obsidian
  [filepath]
  (try
    ;; First try with vault specified
    (let [vault-name "Default%20Vault"
          relative-path "tracker.tmp.md"
          obsidian-uri (str "obsidian://open?vault=" vault-name
                            "&file=" (java.net.URLEncoder/encode relative-path "UTF-8"))]
      (log/info (str "Trying Obsidian URI:" obsidian-uri))
      (sh/sh "open" obsidian-uri))
    ;; Also try direct file opening as backup
    (sh/sh "open" "-a" "Obsidian" filepath)
    (catch Exception e
      (log/error {:error-context :obsidian-open} e "Could not auto-open Obsidian"))))

(defn create-obsidian-temp-file
  [item]
  (let [filepath "/Users/daniel/Documents/Obsidian Vaults/Default Vault/tracker.tmp.md"
        description (or (:description item) "")
        file-already-exists? (.exists (io/file filepath))]
    (try (io/make-parents filepath)
         ;; Only write the file if it doesn't already exist
         (when-not file-already-exists?
           (spit filepath (str "# " (:title item) "\n" "Tracker:" (:id item) "\n\n" description)))
         (open-in-obsidian filepath)
         {:file-already-exists? file-already-exists?}
         (catch Exception e
           (log/error {:error-context :obsidian-edit} e "Failed to create external edit file")
           {:error "Failed to create external edit file"}))))

(defn parse-obsidian-temp-file
  []
  (let [filepath "/Users/daniel/Documents/Obsidian Vaults/Default Vault/tracker.tmp.md"]
    (when (.exists (io/file filepath))
      (let [content (slurp filepath)
            lines (str/split content #"\n")
            ;; Skip first line (title) and second line (Tracker:ID), find first empty line
            desc-start-idx (loop [idx 2]
                             (if (>= idx (count lines))
                               idx
                               (if (str/blank? (nth lines idx)) (inc idx) (recur (inc idx)))))
            description
              (if (< desc-start-idx (count lines)) (str/join "\n" (drop desc-start-idx lines)) "")]
        (log/info (str "Raw content:" (pr-str content)))
        (log/info (str "Parsed description:" (pr-str description)))
        description))))

(defn delete-obsidian-temp-file
  []
  (let [filepath "/Users/daniel/Documents/Obsidian Vaults/Default Vault/tracker.tmp.md"]
    (try (io/delete-file filepath)
         (log/info (str "Deleted temp file:" filepath))
         (catch Exception e
           (log/warn {:error-context :file-cleanup} e "Could not delete temp file")))))
