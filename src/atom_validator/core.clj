(ns atom-validator.core
  "RFC 4287 Atom feed validation library.

  Validates Atom feeds for:
  - Required elements per RFC 4287
  - Valid date formats
  - Feed freshness (updated timestamp)
  - URL validity
  - Semantic consistency (day-of-week in titles)"
  (:require [atom-validator.parser :as parser]
            [atom-validator.rules :as rules]
            [atom-validator.semantic :as semantic]
            [atom-validator.url :as url]))

(def version
  "Library version, read from POM at compile time."
  (delay
    (or (some-> (clojure.java.io/resource "META-INF/maven/org.clojars.apace/atom-validator/pom.properties")
                slurp
                (->> (re-find #"version=(.+)"))
                second)
        "dev")))

(defn- merge-results
  "Merge multiple validation result maps."
  [& results]
  {:errors (vec (mapcat :errors results))
   :warnings (vec (mapcat :warnings results))})

(defn validate-feed
  "Validate an Atom feed. Returns {:valid? bool :errors [...] :warnings [...]}.

   Arguments:
   - feed: Either a parsed feed map or XML string/input-stream

   Options:
   - :semantic? - Enable semantic checks like day-of-week (default true)
   - :strict?   - Treat warnings as errors (default false)

   Example:
     (validate-feed \"<feed>...</feed>\")
     (validate-feed parsed-map {:semantic? false})"
  ([feed] (validate-feed feed {}))
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
      :feed parsed})))

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

(defn parse-feed
  "Parse an Atom feed from XML string or input stream.
   Returns a map with :id, :title, :updated, :entries, etc."
  [source]
  (parser/parse-feed source))

(defn valid?
  "Quick check if a feed is valid. Returns true/false."
  ([feed] (valid? feed {}))
  ([feed opts]
   (:valid? (validate-feed feed opts))))
