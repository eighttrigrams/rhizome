(ns scrapers.atom-feed
  (:require [clojure.string :as str]
            [clojure.xml :as xml]
            [clj-http.client :as http]
            [hickory.core :as hickory])
  (:import [java.io ByteArrayInputStream]))

(defn- tag-name [el] (when (map? el) (name (:tag el))))

(defn- child-text
  [el tag]
  (some (fn [child] (when (= tag (tag-name child)) (first (:content child)))) (:content el)))

(defn- find-child
  [el tag]
  (some (fn [child] (when (= tag (tag-name child)) child)) (:content el)))

(declare node->md)

(defn- children-md [node] (apply str (map node->md (:content node))))

(defn- node->md
  [node]
  (cond
    (string? node) node
    (map? node)
      (let [inner (children-md node)]
        (case (:tag node)
          :a (let [href (get-in node [:attrs :href])]
               (if (seq href) (str "[" (str/trim inner) "](" href ")") inner))
          (:strong :b) (str "**" inner "**")
          (:em :i) (str "*" inner "*")
          :code (str "`" inner "`")
          :br "\n"
          (:p :div) (str "\n\n" inner "\n\n")
          :blockquote (str "\n\n"
                           (->> (str/split-lines (str/trim inner))
                                (map #(str "> " %))
                                (str/join "\n"))
                           "\n\n")
          :li (str "\n- " (str/trim inner))
          (:ul :ol) (str "\n" inner "\n")
          (:h1 :h2 :h3 :h4 :h5 :h6) (str "\n\n**" (str/trim inner) "**\n\n")
          (:script :style) ""
          inner))
    :else ""))

(defn- normalize-md [s] (str/trim (str/replace s #"\n{3,}" "\n\n")))

(defn- html->markdown
  [html-str]
  (normalize-md
    (apply str (map (comp node->md hickory/as-hickory) (hickory/parse-fragment html-str)))))

(defn- entry-summary
  "Markdown rendition of an entry's content (preferred) or summary,
  honoring the atom type attribute. Mirrors tracker's typed-payload
  handling, targeting markdown instead of sanitized HTML."
  [entry]
  (when-let [el (or (find-child entry "content") (find-child entry "summary"))]
    (let [type (get-in el [:attrs :type] "text")
          text (case type
                 "html" (html->markdown (apply str (filter string? (:content el))))
                 "xhtml" (normalize-md (children-md el))
                 (str/trim (apply str (filter string? (:content el)))))]
      (when (seq text) text))))

(defn- link-href
  [entry]
  (let [links (filter (fn [el] (= "link" (tag-name el))) (:content entry))
        preferred (some (fn [el]
                          (let [rel (:rel (:attrs el))]
                            (when (or (nil? rel) (= "alternate" rel)) (:href (:attrs el)))))
                        links)]
    (or preferred (:href (:attrs (first links))))))

(defn- entry->map
  [entry]
  (let [entry-id (child-text entry "id")
        title (child-text entry "title")
        link (link-href entry)
        published (or (child-text entry "published") (child-text entry "updated"))]
    (when (seq entry-id)
      {:entry-id entry-id
       :title title
       :link link
       :published published
       :summary (entry-summary entry)})))

(defn parse-feed
  [xml-string]
  (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes ^String xml-string "UTF-8")))
        title (some (fn [el] (when (= "title" (tag-name el)) (first (:content el))))
                    (:content parsed))
        entries (->> (:content parsed)
                     (filter (fn [el] (= "entry" (tag-name el))))
                     (keep entry->map)
                     vec)]
    {:title title :entries entries}))

(defn fetch-feed
  [url]
  (let [resp (http/get url {:as :string
                            :throw-exceptions false
                            :headers {"User-Agent" "Mozilla/5.0"}})]
    (when (= 200 (:status resp))
      (parse-feed (:body resp)))))
