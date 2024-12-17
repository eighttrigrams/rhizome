(ns repository.insertion.github
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            utils
            [utils.url :as url]
            upload))

(defn match? [title]
  (re-matches #"https://github.com\/.*" title))

(comment
  (match? "https://github.com/taoensso/telemere"))

(defn- get-github-user-id 
  [db 
   user 
   github-platform-id
   github-orgs-id]
  (let [github-user (common/insert-item db 
                                        user
                                        (str "GH@" user) 
                                        #{github-platform-id github-orgs-id}
                                        {:github-user (str "https://github.com/" user)})]
    (:id github-user)))

(defn get-subdomain [url-string]
  (let [url (java.net.URL. url-string)
        host (.getPath url)
        [_ user repo] (str/split host #"\/")]
    [user repo]))

(defn-  convert [url]
  (let [[user repo] (get-subdomain url)]
    [;; short-title
     (str user "/" repo)
     ;; title
     (str user "/" repo)
     ;; link
     url]))

(comment 
  (get-subdomain "https://github.com/eighttrigrams/tracker")
  (convert "https://github.com/eighttrigrams/tracker"))

(defn- create-or-take-github-user-id [db  
                                      user 
                                      github-platform-id 
                                      github-users-id]
  (let [github-user-id (:id (datastore/get-item-by-path db 
                                                        "data->'resource-links'->>'github-user'" 
                                                        (str "https://github.com/" user)))
        github-user-id (or github-user-id (get-github-user-id db 
                                                     user 
                                                     github-platform-id
                                                     github-users-id))]
    github-user-id))

(defn save-article [db url context-ids-set]
  (let [url                (url/url-without-query-params url)
        github-platform-id (common/get-item-or-throw-error db "GitHub")
        github-users-id    (common/get-item-or-throw-error db "GitHub User")
        github-repos-id    (common/get-item-or-throw-error db "GitHub Repo")
        libraries-id       (common/get-item-or-throw-error db "Library")
        [title short-title] (convert url)
        github-user-id     (create-or-take-github-user-id 
                            db
                            (first (get-subdomain url))
                            github-platform-id
                            github-users-id)
        context-ids-set    (conj context-ids-set
                                 (or github-user-id libraries-id) ;; hack 
                                 libraries-id 
                                 github-repos-id
                                 github-platform-id)
        _                  (log/info         (str "context-ids-set: " context-ids-set))
        existing-item      (datastore/get-item-by-path db "data->'resource-links'->>'github-repo'" url)]
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (let [item (common/insert-item db 
                                               title 
                                               short-title
                                               context-ids-set 
                                               {:github-repo url})]
        (log/info (str "created new item" item))
        item))))
