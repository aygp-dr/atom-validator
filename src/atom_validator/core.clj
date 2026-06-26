(ns atom-validator.core
  "Feed validation library supporting Atom, RSS 2.0, and JSON Feed 1.1.

  Validates feeds for:
  - Required elements per RFC 4287 (Atom), RSS 2.0 spec, or JSON Feed 1.1
  - Valid date formats (RFC 3339 for Atom/JSON Feed, RFC 822 for RSS)
  - Feed freshness (updated/lastBuildDate timestamp)
  - URL validity
  - Semantic consistency (day-of-week in titles, Atom only)

  Auto-detects feed format from content (XML root element or JSON structure)."
  (:require [atom-validator.parser :as parser]
            [atom-validator.rules :as rules]
            [atom-validator.semantic :as semantic]
            [atom-validator.url :as url]
            [atom-validator.rss :as rss]
            [atom-validator.jsonfeed :as jsonfeed]
            [atom-validator.http :as http]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringReader]))

(def version
  "Library version, read from POM at runtime."
  (delay
    (or (some-> (io/resource "META-INF/maven/org.clojars.apace/atom-validator/pom.properties")
                slurp
                (->> (re-find #"version=(.+)"))
                second)
        "dev")))

(defn- merge-results
  "Merge multiple validation result maps."
  [& results]
  {:errors (vec (mapcat :errors results))
   :warnings (vec (mapcat :warnings results))})

(defn- json-feed?
  "Check if source looks like JSON (starts with { after trimming whitespace)."
  [source]
  (when (string? source)
    (str/starts-with? (str/triml source) "{")))

(defn detect-feed-format
  "Detect feed format from content.
   Returns :atom, :rss, :json-feed, or :unknown."
  [source]
  (let [source-str (if (string? source) source (slurp source))]
    (cond
      ;; JSON Feed detection
      (json-feed? source-str)
      :json-feed

      ;; XML-based formats
      :else
      (try
        (let [root (xml/parse (StringReader. source-str))
              root-tag (name (:tag root))]
          (cond
            (= root-tag "feed") :atom
            (= root-tag "rss") :rss
            (= root-tag "RDF") :rss  ; RSS 1.0 uses RDF root
            :else :unknown))
        (catch Exception _
          :unknown)))))

(defn- source-to-string
  "Convert source to string if needed for re-parsing."
  [source]
  (if (string? source)
    source
    (slurp source)))

(defn validate-atom-feed
  "Validate an Atom feed specifically. Returns {:valid? bool :errors [...] :warnings [...] :feed map}."
  ([feed] (validate-atom-feed feed {}))
  ([feed {:keys [semantic? strict?] :or {semantic? true strict? false}}]
   (let [parsed (if (map? feed) feed (parser/parse-feed feed))
         structure-result (rules/validate-feed-structure parsed)
         url-result (url/validate-feed-urls parsed)
         semantic-result (if semantic?
                           (semantic/validate-feed-semantics parsed)
                           {:errors [] :warnings []})
         combined (merge-results structure-result url-result semantic-result)
         all-issues (if strict?
                      (concat (:errors combined) (:warnings combined))
                      (:errors combined))]
     {:valid? (empty? all-issues)
      :errors (:errors combined)
      :warnings (:warnings combined)
      :feed (assoc parsed :format :atom)})))

(defn validate-json-feed
  "Validate a JSON Feed. Returns {:valid? bool :errors [...] :warnings [...] :feed map}.

   Arguments:
   - feed: Either a parsed feed map or JSON string

   Options:
   - :strict? - Treat warnings as errors (default false)

   Example:
     (validate-json-feed \"{\\\"version\\\":\\\"https://jsonfeed.org/version/1.1\\\",...}\")"
  ([feed] (validate-json-feed feed {}))
  ([feed opts]
   (let [result (jsonfeed/validate-json-feed feed opts)]
     (update result :feed assoc :format :json-feed))))

(defn- guard-parse
  "Run a validation thunk that parses feed content, converting a parse failure
  (malformed/non-feed input, e.g. an HTML error page) into a normal validation
  result instead of letting the exception escape. Keeps the public validate-feed
  API from throwing on garbage input.

  The error :code is parser-aware: an XML stream failure yields :invalid-xml,
  while any other parse failure (e.g. malformed JSON-Feed input, where
  data.json/read-str throws) yields :invalid-json. Tagging every failure
  :invalid-xml mislabels JSON-Feed garbage as an XML problem."
  [thunk]
  (try
    (thunk)
    (catch javax.xml.stream.XMLStreamException e
      {:valid? false
       :warnings []
       :errors [{:type :error
                 :code :invalid-xml
                 :message (str "Feed is not well-formed XML: " (.getMessage e))
                 :path []}]})
    (catch Exception e
      {:valid? false
       :warnings []
       :errors [{:type :error
                 :code :invalid-json
                 :message (str "Could not parse feed: " (.getMessage e))
                 :path []}]})))

(defn validate-feed
  "Validate a feed (Atom, RSS, or JSON Feed). Auto-detects format from content.
   Returns {:valid? bool :errors [...] :warnings [...] :feed map}.

   Arguments:
   - feed: Either a parsed feed map, an http(s) URL string, an XML/JSON string,
           or an input-stream.

   If feed is a string starting with \"http://\" or \"https://\", it is fetched
   over HTTP first (see atom-validator.http/fetch-feed). On fetch failure, the
   returned map will have :valid? false and :http metadata.

   Options:
   - :semantic? - Enable semantic checks like day-of-week (default true, Atom only)
   - :strict?   - Treat warnings as errors (default false)
   - :format    - Force format (:atom, :rss, or :json-feed), auto-detects if not specified
   - :fetch?    - When false, do NOT auto-fetch URLs (default true). Useful if
                  the caller wants to treat a URL-like string as raw content.
   - HTTP options (when fetching): :timeout-seconds, :max-redirects,
     :validate-content-type?, :head-check? - see atom-validator.http/fetch-feed

   Example:
     (validate-feed \"<feed>...</feed>\")             ; Atom feed
     (validate-feed \"<rss>...</rss>\")               ; RSS feed
     (validate-feed \"{\\\"version\\\":...}\")          ; JSON Feed
     (validate-feed \"https://example.com/feed.xml\") ; Fetched via HTTP
     (validate-feed parsed-map {:semantic? false})"
  ([feed] (validate-feed feed {}))
  ([feed {:keys [format fetch?] :or {fetch? true} :as opts}]
   (cond
     ;; Already parsed with format marker
     (and (map? feed) (:format feed))
     (case (:format feed)
       :rss (rss/validate-rss-feed feed opts)
       :json-feed (validate-json-feed feed opts)
       (validate-atom-feed feed opts))

     ;; Pre-parsed map without format marker - assume Atom for backwards compatibility
     (map? feed)
     (validate-atom-feed feed opts)

     ;; URL string: fetch then validate
     (and fetch? (http/url? feed))
     (let [fetch-result (http/fetch-feed feed opts)
           http-meta {:url (:url fetch-result)
                      :status (:status fetch-result)
                      :content-type (:content-type fetch-result)
                      :redirects (:redirects fetch-result)}]
       (if (:ok? fetch-result)
         (assoc (validate-feed (:body fetch-result) (dissoc opts :fetch?))
                :http http-meta)
         {:valid? false
          :errors (:errors fetch-result)
          :warnings []
          :http http-meta}))

     ;; Force format specified
     (= format :rss)
     (guard-parse #(rss/validate-rss-feed feed opts))

     (= format :atom)
     (guard-parse #(validate-atom-feed feed opts))

     (= format :json-feed)
     (guard-parse #(validate-json-feed feed opts))

     ;; Auto-detect from content
     :else
     (guard-parse
      #(let [source-str (source-to-string feed)
             detected-format (detect-feed-format source-str)]
         (case detected-format
           :rss (rss/validate-rss-feed source-str opts)
           :atom (validate-atom-feed source-str opts)
           :json-feed (validate-json-feed source-str opts)
           ;; Unknown format - try Atom, it will produce validation errors
           (validate-atom-feed source-str opts)))))))

(defn validate-entry
  "Validate a single Atom entry. Returns {:valid? bool :errors [...] :warnings [...]}.

   Arguments:
   - entry: A map with :id, :title, :updated, :links, etc.

   Options:
   - :semantic? - Enable semantic checks (default true)
   - :strict?   - Treat warnings as errors (default false)

   Example:
     (validate-entry {:title \"Morning Brief: Thursday, June 19\"
                      :updated \"2026-06-19T00:00:00Z\"
                      :id \"urn:uuid:123\"})"
  ([entry] (validate-entry entry {}))
  ([entry {:keys [semantic? strict?] :or {semantic? true strict? false}}]
   (let [structure-errors (rules/validate-entry entry 0)
         url-errors (url/validate-entry-urls entry 0)
         semantic-errors (if semantic?
                           (semantic/validate-entry-semantics entry 0)
                           [])
         all-issues (concat structure-errors url-errors semantic-errors)
         errors (vec (filter #(= :error (:type %)) all-issues))
         warnings (vec (filter #(= :warning (:type %)) all-issues))
         final-issues (if strict?
                        (concat errors warnings)
                        errors)]
     {:valid? (empty? final-issues)
      :errors errors
      :warnings warnings})))

(defn validate-json-item
  "Validate a single JSON Feed item. Returns {:valid? bool :errors [...] :warnings [...]}.

   Arguments:
   - item: A map with :id, :content_html/:content_text, etc.

   Options:
   - :strict? - Treat warnings as errors (default false)

   Example:
     (validate-json-item {:id \"1\" :content_text \"Hello world\"})"
  ([item] (validate-json-item item {}))
  ([item opts]
   (jsonfeed/validate-json-item item opts)))

(defn parse-feed
  "Parse an Atom, RSS, or JSON Feed from string or input stream.
   Auto-detects format from content.
   Returns a map with feed data. Includes :format key (:atom, :rss, or :json-feed).

   Options:
   - :format - Force format (:atom, :rss, or :json-feed), auto-detects if not specified"
  ([source] (parse-feed source {}))
  ([source {:keys [format]}]
   (cond
     (= format :rss)
     (rss/parse-rss-feed source)

     (= format :atom)
     (assoc (parser/parse-feed source) :format :atom)

     (= format :json-feed)
     (jsonfeed/parse-json-feed source)

     :else
     (let [source-str (source-to-string source)
           detected-format (detect-feed-format source-str)]
       (case detected-format
         :rss (rss/parse-rss-feed source-str)
         :json-feed (jsonfeed/parse-json-feed source-str)
         ;; Default to Atom
         (assoc (parser/parse-feed source-str) :format :atom))))))

(defn parse-json-feed
  "Parse a JSON Feed from JSON string or reader.
   Returns a Clojure map."
  [source]
  (jsonfeed/parse-json-feed source))

(defn valid?
  "Quick check if a feed is valid. Returns true/false."
  ([feed] (valid? feed {}))
  ([feed opts]
   (:valid? (validate-feed feed opts))))
