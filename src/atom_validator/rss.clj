(ns atom-validator.rss
  "RSS 2.0 feed parsing and validation.
   Supports RSS 2.0 specification with validation for:
   - Required channel elements (title, link, description)
   - Optional channel elements (language, pubDate, lastBuildDate, generator)
   - Item elements (title OR description required, link, pubDate, guid, enclosure, category)"
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.io StringReader]
           [java.text SimpleDateFormat ParseException]
           [java.util Locale TimeZone]))

;; =============================================================================
;; RFC 822 Date Parsing (RSS 2.0 uses RFC 822 dates)
;; =============================================================================

(def rfc822-formats
  "RFC 822 date formats commonly used in RSS feeds."
  ["EEE, dd MMM yyyy HH:mm:ss Z"
   "EEE, dd MMM yyyy HH:mm:ss zzz"
   "dd MMM yyyy HH:mm:ss Z"
   "dd MMM yyyy HH:mm:ss zzz"
   "EEE, dd MMM yyyy HH:mm Z"
   "EEE, dd MMM yy HH:mm:ss Z"])

(defn parse-rfc822
  "Parse an RFC 822 datetime string. Returns java.util.Date or nil if invalid."
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (let [trimmed (str/trim s)]
      (some (fn [fmt]
              (try
                (let [sdf (SimpleDateFormat. fmt Locale/US)]
                  (.setTimeZone sdf (TimeZone/getTimeZone "UTC"))
                  (.parse sdf trimmed))
                (catch ParseException _ nil)))
            rfc822-formats))))

;; =============================================================================
;; XML Parsing Helpers (similar to parser.clj style)
;; =============================================================================

(defn- find-children
  "Find child elements by local name."
  [element tag-name]
  (filter #(and (map? %)
                (= (name (:tag %)) tag-name))
          (:content element)))

(defn- find-child
  "Find first child element by local name."
  [element tag-name]
  (first (find-children element tag-name)))

(defn- text-content
  "Extract text content from an element."
  [element]
  (when element
    (str/join "" (filter string? (:content element)))))

;; =============================================================================
;; RSS Item Parsing
;; =============================================================================

(defn- parse-enclosure
  "Parse an enclosure element into a map."
  [enclosure-el]
  (when enclosure-el
    {:url (get-in enclosure-el [:attrs :url])
     :length (get-in enclosure-el [:attrs :length])
     :type (get-in enclosure-el [:attrs :type])}))

(defn- parse-guid
  "Parse a guid element into a map."
  [guid-el]
  (when guid-el
    {:value (text-content guid-el)
     :is-perma-link (get-in guid-el [:attrs :isPermaLink] "true")}))

(defn parse-item
  "Parse an RSS item element into a Clojure map."
  [item-el]
  {:title (text-content (find-child item-el "title"))
   :link (text-content (find-child item-el "link"))
   :description (text-content (find-child item-el "description"))
   :author (text-content (find-child item-el "author"))
   :pub-date (text-content (find-child item-el "pubDate"))
   :guid (parse-guid (find-child item-el "guid"))
   :enclosure (parse-enclosure (find-child item-el "enclosure"))
   :categories (mapv text-content (find-children item-el "category"))
   :comments (text-content (find-child item-el "comments"))
   :source (text-content (find-child item-el "source"))})

;; =============================================================================
;; RSS Channel/Feed Parsing
;; =============================================================================

(defn parse-rss-feed
  "Parse an RSS 2.0 feed from XML string or input stream.
   Returns a map with :title, :link, :description, :items, etc."
  [source]
  (let [xml-source (if (string? source)
                     (StringReader. source)
                     source)
        root (xml/parse xml-source)
        channel (find-child root "channel")]
    {:format :rss
     :version (get-in root [:attrs :version] "2.0")
     :title (text-content (find-child channel "title"))
     :link (text-content (find-child channel "link"))
     :description (text-content (find-child channel "description"))
     :language (text-content (find-child channel "language"))
     :copyright (text-content (find-child channel "copyright"))
     :managing-editor (text-content (find-child channel "managingEditor"))
     :web-master (text-content (find-child channel "webMaster"))
     :pub-date (text-content (find-child channel "pubDate"))
     :last-build-date (text-content (find-child channel "lastBuildDate"))
     :generator (text-content (find-child channel "generator"))
     :docs (text-content (find-child channel "docs"))
     :ttl (text-content (find-child channel "ttl"))
     :categories (mapv text-content (find-children channel "category"))
     :items (mapv parse-item (find-children channel "item"))}))

;; =============================================================================
;; Error/Warning Constructors (matching rules.clj style)
;; =============================================================================

(defn- error
  "Create an error map."
  ([code message] {:type :error :code code :message message})
  ([code message path] {:type :error :code code :message message :path path}))

(defn- warning
  "Create a warning map."
  ([code message] {:type :warning :code code :message message})
  ([code message path] {:type :warning :code code :message message :path path}))

;; =============================================================================
;; Channel-level Validation
;; =============================================================================

(defn validate-channel-title
  "Channel MUST have a title element."
  [feed]
  (when (or (nil? (:title feed)) (str/blank? (:title feed)))
    [(error :missing-channel-title "Channel must contain a title element")]))

(defn validate-channel-link
  "Channel MUST have a link element."
  [feed]
  (when (or (nil? (:link feed)) (str/blank? (:link feed)))
    [(error :missing-channel-link "Channel must contain a link element")]))

(defn validate-channel-description
  "Channel MUST have a description element."
  [feed]
  (when (or (nil? (:description feed)) (str/blank? (:description feed)))
    [(error :missing-channel-description "Channel must contain a description element")]))

(defn validate-channel-pubdate
  "Channel pubDate must be valid RFC 822 format if present."
  [feed]
  (when-let [pub-date (:pub-date feed)]
    (when (and (not (str/blank? pub-date))
               (nil? (parse-rfc822 pub-date)))
      [(error :invalid-pubdate
              (str "Channel pubDate must be valid RFC 822 datetime: " pub-date)
              [:pub-date])])))

(defn validate-channel-last-build-date
  "Channel lastBuildDate must be valid RFC 822 format if present."
  [feed]
  (when-let [last-build (:last-build-date feed)]
    (when (and (not (str/blank? last-build))
               (nil? (parse-rfc822 last-build)))
      [(error :invalid-pubdate
              (str "Channel lastBuildDate must be valid RFC 822 datetime: " last-build)
              [:last-build-date])])))

;; =============================================================================
;; Item-level Validation
;; =============================================================================

(defn validate-item-content
  "Item MUST have at least a title OR description element."
  [item idx]
  (let [has-title? (and (:title item) (not (str/blank? (:title item))))
        has-desc? (and (:description item) (not (str/blank? (:description item))))]
    (when-not (or has-title? has-desc?)
      [(error :missing-item-content
              "Item must contain at least a title or description element"
              [:items idx])])))

(defn validate-item-pubdate
  "Item pubDate must be valid RFC 822 format if present."
  [item idx]
  (when-let [pub-date (:pub-date item)]
    (when (and (not (str/blank? pub-date))
               (nil? (parse-rfc822 pub-date)))
      [(error :invalid-pubdate
              (str "Item pubDate must be valid RFC 822 datetime: " pub-date)
              [:items idx :pub-date])])))

(defn validate-item-guid
  "Item guid should be unique within the feed.
   Note: This validates format only; uniqueness is checked at feed level."
  [item idx]
  (when-let [guid (:guid item)]
    (when (and guid (str/blank? (:value guid)))
      [(error :invalid-guid
              "Item guid must not be empty if present"
              [:items idx :guid])])))

(defn validate-item
  "Validate a single RSS item. Returns a sequence of errors/warnings."
  [item idx]
  (concat
   (validate-item-content item idx)
   (validate-item-pubdate item idx)
   (validate-item-guid item idx)))

;; =============================================================================
;; Feed-level Validation
;; =============================================================================

(defn validate-guid-uniqueness
  "Check that all guids in the feed are unique."
  [feed]
  (let [guids (keep #(get-in % [:guid :value]) (:items feed))
        dup-guids (for [[guid items] (group-by identity guids)
                        :when (> (count items) 1)]
                    guid)]
    (mapv (fn [dup-guid]
            (warning :duplicate-guid
                     (str "Duplicate guid found: " dup-guid)))
          dup-guids)))

(defn validate-rss-structure
  "Validate RSS feed structure. Returns {:errors [...] :warnings [...]}."
  [feed]
  (let [channel-issues (concat
                        (validate-channel-title feed)
                        (validate-channel-link feed)
                        (validate-channel-description feed)
                        (validate-channel-pubdate feed)
                        (validate-channel-last-build-date feed)
                        (validate-guid-uniqueness feed))
        item-issues (mapcat (fn [[idx item]]
                              (validate-item item idx))
                            (map-indexed vector (:items feed)))]
    {:errors (vec (filter #(= :error (:type %)) (concat channel-issues item-issues)))
     :warnings (vec (filter #(= :warning (:type %)) (concat channel-issues item-issues)))}))

;; =============================================================================
;; URL Validation for RSS
;; =============================================================================

(defn validate-rss-urls
  "Validate URLs in RSS feed (link elements, enclosures).
   Returns {:errors [...] :warnings [...]}."
  [_feed]
  ;; For now, return empty - URL validation can be added later
  ;; using the existing atom-validator.url namespace
  {:errors [] :warnings []})

;; =============================================================================
;; Combined RSS Validation
;; =============================================================================

(defn validate-rss-feed
  "Validate an RSS 2.0 feed. Returns {:valid? bool :errors [...] :warnings [...] :feed map}.

   Arguments:
   - feed: Either a parsed feed map or XML string/input-stream

   Options:
   - :strict? - Treat warnings as errors (default false)"
  ([feed] (validate-rss-feed feed {}))
  ([feed {:keys [strict?] :or {strict? false}}]
   (let [parsed (if (and (map? feed) (:format feed))
                  feed
                  (parse-rss-feed feed))
         structure-result (validate-rss-structure parsed)
         url-result (validate-rss-urls parsed)
         combined {:errors (vec (concat (:errors structure-result) (:errors url-result)))
                   :warnings (vec (concat (:warnings structure-result) (:warnings url-result)))}
         all-issues (if strict?
                      (concat (:errors combined) (:warnings combined))
                      (:errors combined))]
     {:valid? (empty? all-issues)
      :errors (:errors combined)
      :warnings (:warnings combined)
      :feed parsed})))
