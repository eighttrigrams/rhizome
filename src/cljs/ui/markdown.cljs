(ns ui.markdown
  "Colours for the fenced code blocks in a rendered description.

   A description is markdown and has always been rendered by react-markdown,
   which emits a fenced block as `<pre><code class=\"language-clj\">` and leaves
   it at that -- so a Clojure form in a note read as grey monospace prose. The
   swe context is largely code, and cookbook, the sibling app whose recipes are
   also mostly code, already answered this with highlight.js. Same library and
   same version here, so the two apps colour a form the same way.

   The mechanism differs, because the markdown pipelines do. Cookbook renders
   through marked and replaces marked's `code` renderer; here the equivalent
   seam is react-markdown's `components` map, one entry of which is what the
   rest of this namespace builds. The alternative -- rehype-highlight -- would
   do the same job through the hast tree, but it arrives with lowlight and its
   whole common-language set behind it, where this is one dependency shared with
   a sibling and a grammar list this app chose.

   Nothing sanitizes, and unlike cookbook nothing needs to. There the injected
   HTML was marked's own output for text an agent wrote, on a page served to
   anonymous visitors. react-markdown does not render raw HTML at all unless it
   is handed rehype-raw, and it is not; the only string that reaches
   `dangerouslySetInnerHTML` below is what highlight.js returned, which is the
   block's text escaped by the highlighter and wrapped in its own spans."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/clojure" :as hljs-clojure]
            ["highlight.js/lib/languages/bash" :as hljs-bash]
            ["highlight.js/lib/languages/javascript" :as hljs-javascript]
            ["highlight.js/lib/languages/python" :as hljs-python]
            ["highlight.js/lib/languages/sql" :as hljs-sql]
            ["highlight.js/lib/languages/yaml" :as hljs-yaml]
            ["highlight.js/lib/languages/json" :as hljs-json]))

(defn- configure!
  "highlight.js is imported as its core plus the grammars named here rather than
   whole: the package carries ~190 of them and a bundle should only pay for the
   ones a description is likely to hold. What that means is this app's own
   vocabulary -- Clojure back and front, shell, the sqlite it stores itself in,
   compose and edn-adjacent yaml, json, and the js and python that turn up in a
   note about somebody else's code. `clj` and `edn` come free with clojure,
   `sh`/`zsh` with bash, `js`/`jsx` with javascript, `py` with python and `yml`
   with yaml, so the fence a note already carries is covered by the name it
   already uses.

   `shell` and `console` are registered by hand, as in cookbook: neither is an
   alias of bash in the package, and a terminal transcript is what a fence in a
   how-do-I note tends to be labelled.

   A fence naming anything else is not an error -- it renders as an
   unhighlighted block, which is the honest rendering of a language this bundle
   cannot read, and the list above is one line to extend."
  []
  (.registerLanguage hljs "clojure" hljs-clojure)
  (.registerLanguage hljs "bash" hljs-bash)
  (.registerLanguage hljs "javascript" hljs-javascript)
  (.registerLanguage hljs "python" hljs-python)
  (.registerLanguage hljs "sql" hljs-sql)
  (.registerLanguage hljs "yaml" hljs-yaml)
  (.registerLanguage hljs "json" hljs-json)
  (.registerAliases hljs #js ["shell" "console"] #js {:languageName "bash"}))

;; Once, not once per hot reload. The var exists to hold that single call, so
;; nothing reads it.
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private configured (configure!))

(defn- fence-language
  "The language a block is to be highlighted as, out of the `language-x` class
   react-markdown puts on the `<code>` -- or nil, which is every case the
   component below leaves alone.

   nil covers three different things on purpose: inline code, which carries no
   class at all, a fence with no language on it, and a fence naming a grammar
   this bundle did not register. All three should render as they did before.

   `getLanguage` answering is also what makes the name safe to interpolate into
   a class attribute: it answers only for a registered name or one of its
   aliases, which is a fixed set of identifiers rather than anything an author
   typed into a fence."
  [class-name]
  (when-let [lang (some-> (when (string? class-name) (re-find #"language-(\S+)" class-name))
                          second
                          str/lower-case)]
    (when (.getLanguage hljs lang) lang)))

(defn- code-text
  "The block's source, out of the children react-markdown hands the component.

   The trailing newline goes: mdast-util-to-hast appends one to every code
   node's text, and inside a `<pre>` that survives as a blank last line -- with
   a background on the block, which is what makes the colours legible, an empty
   line is suddenly something you can see.

   `$`, not `\\z`: these regexes are JavaScript's, where `\\z` is not an anchor
   at all but a literal z, so the newline would have stayed and taken the fault
   with it -- silently, since it only shows as one blank line at the foot of a
   block."
  [children]
  (-> (->> (if (array? children) (array-seq children) [children])
           (filter string?)
           (str/join))
      (str/replace #"\n$" "")))

(defn- code-component
  [props]
  (let [children (aget props "children")
        lang (fence-language (aget props "className"))]
    (r/as-element
      (if lang
        [:code
         {:class (str "hljs language-" lang)
          :dangerouslySetInnerHTML
            (r/unsafe-html (.-value (.highlight hljs (code-text children) #js {:language lang})))}]
        ;; No class where react-markdown would have left `language-rockstar`
        ;; standing: nothing an author writes should reach the page as a class
        ;; name, and the only thing lost is CSS being able to name a language
        ;; this bundle cannot read.
        [:code (into [:<>] (if (array? children) (array-seq children) [children]))]))))

(def components
  "The `components` entry that turns highlighting on, for the react-markdown
   calls that render a description. Merged into the call site's own map rather
   than replacing it -- the item's description also overrides `a` and `img`, to
   resolve the bare-id links and images an item can carry.

   Deliberately not applied to the two places a *title* is rendered as markdown:
   a title is a phrase, and a fence cannot open in one."
  {:code code-component})
