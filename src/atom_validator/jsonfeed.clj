(ns atom-validator.jsonfeed
  "JSON Feed 1.1 validation per https://www.jsonfeed.org/version/1.1/

   Validates JSON Feeds for:
   - Required fields (version, title, items)
   - Item requirements (id, content_html or content_text)
   - Valid date formats (ISO 8601/RFC 3339)
   - URL validity (home_page_url, feed_url, item url)"
  (:require [atom-validator.url :as url]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; Valid JSON Feed version URLs
(def valid-versions
  #{"https://jsonfeed.org/version/1"
    "https://jsonfeed.org/version/1.1"})

;; ISO 8601 / RFC 3339 date pattern
(def iso8601-pattern
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(.\d+)?(Z|[+-]\d{2}:\d{2})$")

(defn- error
  "Create an error map."
  ([code message] {:type :error :code code :message message})
  ([code message path] {:type :error :code code :message message :path path}))

(defn- warning
  "Create a warning map."
  ([code message] {:type :warning :code code :message message})
  ([code message path] {:type :warning :code code :message message :path path}))

;; Parsing

(defn parse-json-feed
  "Parse a JSON Feed from a JSON string or reader.
   Returns a Clojure map."
  [source]
  (cond
    (map? source) source
    (string? source) (json/read-str source :key-fn keyword)
    :else (json/read source :key-fn keyword)))

;; Date validation

(defn valid-iso8601?
  "Check if string is a valid ISO 8601/RFC 3339 datetime."
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (boolean (re-matches iso8601-pattern s))))

;; Feed-level validation

(defn validate-version
  "Feed MUST have a version field with a valid jsonfeed.org URL."
  [feed]
  (let [version (:version feed)]
    (cond
      (nil? version)
      [(error :missing-version "Feed must contain a version field")]

      (not (valid-versions version))
      [(error :invalid-version
              (str "Feed version must be a valid jsonfeed.org URL, got: " version))]

      :else [])))

(defn validate-feed-title
  "Feed MUST have a title field."
  [feed]
  (when (or (nil? (:title feed)) (str/blank? (:title feed)))
    [(error :missing-title "Feed must contain a title field")]))

(defn validate-feed-items
  "Feed MUST have an items array."
  [feed]
  (cond
    (nil? (:items feed))
    [(error :missing-items "Feed must contain an items array")]

    (not (sequential? (:items feed)))
    [(error :invalid-items "Feed items must be an array")]

    :else []))

(defn validate-feed-urls
  "Validate optional feed-level URLs."
  [feed]
  (let [check-url (fn [field path]
                    (when-let [u (get feed field)]
                      (when-let [err (url/validate-url u path)]
                        [(assoc err :path [field])])))]
    (concat
     (check-url :home_page_url [:home_page_url])
     (check-url :feed_url [:feed_url]))))

(defn validate-feed-authors
  "Validate authors array if present."
  [feed]
  (when-let [authors (:authors feed)]
    (when (and (sequential? authors)
               (some #(and (map? %) (str/blank? (:name %))) authors))
      [(warning :author-missing-name
                "Author objects should have a name field")])))

;; Item-level validation

(defn validate-item-id
  "Each item MUST have an id field."
  [item idx]
  (when (or (nil? (:id item)) (str/blank? (str (:id item))))
    [(error :missing-item-id "Item must contain an id field"
            [:items idx :id])]))

(defn validate-item-content
  "Each item MUST have content_html or content_text."
  [item idx]
  (let [has-html? (and (:content_html item)
                       (not (str/blank? (:content_html item))))
        has-text? (and (:content_text item)
                       (not (str/blank? (:content_text item))))]
    (when-not (or has-html? has-text?)
      [(error :missing-item-content
              "Item must contain content_html or content_text"
              [:items idx])])))

(defn validate-item-dates
  "Validate optional date_published and date_modified fields."
  [item idx]
  (let [check-date (fn [field]
                     (when-let [d (get item field)]
                       (when-not (valid-iso8601? d)
                         [(error :invalid-date
                                 (str "Item " (name field) " must be valid ISO 8601: " d)
                                 [:items idx field])])))]
    (concat
     (check-date :date_published)
     (check-date :date_modified))))

(defn validate-item-urls
  "Validate optional URL fields in items."
  [item idx]
  (let [check-url (fn [field]
                    (when-let [u (get item field)]
                      (when-let [err (url/validate-url u [:items idx field])]
                        [(assoc err :path [:items idx field])])))]
    (concat
     (check-url :url)
     (check-url :external_url)
     (check-url :image)
     (check-url :banner_image))))

(defn validate-item
  "Validate a single JSON Feed item. Returns a sequence of errors/warnings."
  [item idx]
  (concat
   (validate-item-id item idx)
   (validate-item-content item idx)
   (validate-item-dates item idx)
   (validate-item-urls item idx)))

;; Main validation

(defn validate-json-feed
  "Validate a JSON Feed. Returns {:valid? bool :errors [...] :warnings [...] :feed map}.

   Arguments:
   - feed: Either a parsed feed map or JSON string

   Options:
   - :strict? - Treat warnings as errors (default false)

   Example:
     (validate-json-feed \"{\\\"version\\\":\\\"https://jsonfeed.org/version/1.1\\\",...}\")
     (validate-json-feed parsed-map {:strict? true})"
  ([feed] (validate-json-feed feed {}))
  ([feed {:keys [strict?] :or {strict? false}}]
   (let [parsed (if (map? feed) feed (parse-json-feed feed))
         feed-issues (concat
                      (validate-version parsed)
                      (validate-feed-title parsed)
                      (validate-feed-items parsed)
                      (validate-feed-urls parsed)
                      (validate-feed-authors parsed))
         item-issues (when (sequential? (:items parsed))
                       (mapcat (fn [[idx item]]
                                 (validate-item item idx))
                               (map-indexed vector (:items parsed))))
         all-issues (concat feed-issues item-issues)
         errors (vec (filter #(= :error (:type %)) all-issues))
         warnings (vec (filter #(= :warning (:type %)) all-issues))
         final-issues (if strict?
                        (concat errors warnings)
                        errors)]
     {:valid? (empty? final-issues)
      :errors errors
      :warnings warnings
      :feed parsed})))

(defn validate-json-item
  "Validate a single JSON Feed item. Returns {:valid? bool :errors [...] :warnings [...]}.

   Arguments:
   - item: A map with :id, :content_html/:content_text, etc.

   Options:
   - :strict? - Treat warnings as errors (default false)"
  ([item] (validate-json-item item {}))
  ([item {:keys [strict?] :or {strict? false}}]
   (let [all-issues (validate-item item 0)
         errors (vec (filter #(= :error (:type %)) all-issues))
         warnings (vec (filter #(= :warning (:type %)) all-issues))
         final-issues (if strict?
                        (concat errors warnings)
                        errors)]
     {:valid? (empty? final-issues)
      :errors errors
      :warnings warnings})))

(defn valid-json-feed?
  "Quick check if a JSON Feed is valid. Returns true/false."
  ([feed] (valid-json-feed? feed {}))
  ([feed opts]
   (:valid? (validate-json-feed feed opts))))
