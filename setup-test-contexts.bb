#!/usr/bin/env bb

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/postgresql "0.1.0")
(require '[pod.babashka.postgresql :as pg])

(def db {:dbtype   "postgresql"
         :dbname   "cometoid_dev"
         :user     "daniel"
         :password "abcdef"
         :port     5437
         :hostname "127.0.0.1"})

(defn clear-database []
  (println "Clearing test database...")
  (pg/execute! db ["DELETE FROM relations WHERE target_id > 0 OR owner_id > 0"])
  (pg/execute! db ["DELETE FROM items WHERE id > 0"]))

(defn create-context [title]
  (let [result (pg/execute-one! db 
                                ["INSERT INTO items (title, short_title, data, is_context, inserted_at, updated_at, updated_at_ctx) VALUES (?, '', '{}', true, NOW(), NOW(), NOW()) RETURNING id, title"
                                 title])]
    (println "Created context:" title "with id" (:items/id result))
    (:items/id result)))

(defn setup-contexts []
  (println "Setting up required contexts...")
  (let [;; Basic file type contexts
        files-contexts ["Files" "Documents" "Audio" "Video" "Image" 
                       "MP3s" "OGGs" "M4As" "WAVs" "MP4s" "FLVs" "MOVs"
                       "PDFs" "TIFFs" "JPEGs" "PNGs" "WEBPs"]
        
        ;; Platform contexts
        platform-contexts ["YouTube" "Substack" "GitHub" "Apple Podcasts" "Twitter"]
        
        ;; Content type contexts  
        content-contexts ["YouTube Videos" "YouTube Channels" "Substacks" "Articles" 
                         "Podcast Episodes" "Podcasts" "GitHub Repo" "GitHub User"
                         "Twitter Handles" "Poasts" "Library" "Video"]
        
        ;; Year contexts (common years)
        year-contexts ["2020" "2021" "2022" "2023" "2024" "2025"]
        
        ;; All contexts to create
        all-contexts (concat ["Imports"]
                             files-contexts platform-contexts content-contexts year-contexts)]
    
    (doseq [context all-contexts]
      (create-context context))
    
    (println (str "Created " (count all-contexts) " contexts successfully!"))))

(defn main []
  (try
    (clear-database)
    (setup-contexts)
    (println "Test database setup complete!")
    (catch Exception e
      (println "Error setting up test database:")
      (println (.getMessage e))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))