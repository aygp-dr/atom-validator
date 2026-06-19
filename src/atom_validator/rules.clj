(ns atom-validator.rules
  "RFC 4287 structural validation rules for Atom feeds."
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.string :as str]))

(def rfc3339-formatter
  "RFC 3339 date-time formatter."
  (tf/formatters :date-time))

(def rfc3339-no-ms-formatter
  "RFC 3339 without milliseconds."
  (tf/formatter "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn parse-datetime
  "Parse an RFC 3339 datetime string. Returns nil if invalid."
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (try
      (tf/parse rfc3339-formatter s)
      (catch Exception _
        (try
          (tf/parse rfc3339-no-ms-formatter s)
          (catch Exception _
            nil))))))

(defn- error
  "Create an error map."
  ([code message] {:type :error :code code :message message})
  ([code message path] {:type :error :code code :message message :path path}))

(defn- warning
  "Create a warning map."
  ([code message] {:type :warning :code code :message message})
  ([code message path] {:type :warning :code code :message message :path path}))

;; Feed-level validation

(defn validate-feed-id
  "Feed MUST have exactly one id element."
  [feed]
  (cond
    (nil? (:id feed))
    [(error :missing-feed-id "Feed must contain an id element")]
    (str/blank? (:id feed))
    [(error :empty-feed-id "Feed id element must not be empty")]
    :else []))

(defn validate-feed-title
  "Feed MUST have exactly one title element."
  [feed]
  (when (or (nil? (:title feed)) (str/blank? (:title feed)))
    [(error :missing-feed-title "Feed must contain a title element")]))

(defn validate-feed-updated
  "Feed MUST have exactly one updated element with valid RFC 3339 date."
  [feed]
  (cond
    (nil? (:updated feed))
    [(error :missing-feed-updated "Feed must contain an updated element")]
    (nil? (parse-datetime (:updated feed)))
    [(error :invalid-feed-updated
            (str "Feed updated must be valid RFC 3339 datetime: " (:updated feed)))]
    :else []))

(defn validate-feed-author
  "Feed SHOULD have author(s), or all entries must have authors."
  [feed]
  (let [feed-has-author? (seq (:authors feed))
        entries-without-author (filter #(empty? (:authors %)) (:entries feed))]
    (when (and (not feed-has-author?)
               (seq entries-without-author))
      [(warning :missing-authors
                "Feed has no author and some entries lack authors")])))

(defn validate-feed-freshness
  "Feed updated timestamp should be >= the newest entry's updated timestamp.
   This is Issue #2 from the validator requirements."
  [feed]
  (let [feed-updated (parse-datetime (:updated feed))
        entry-dates (keep #(parse-datetime (:updated %)) (:entries feed))
        max-entry-date (when (seq entry-dates)
                         (reduce #(if (t/after? %1 %2) %1 %2) entry-dates))]
    (when (and feed-updated max-entry-date
               (t/before? feed-updated max-entry-date))
      [(error :stale-feed-updated
              (str "Feed updated (" (:updated feed) ") is older than newest entry")
              [:updated])])))

;; Entry-level validation

(defn validate-entry-id
  "Entry MUST have exactly one id element."
  [entry idx]
  (cond
    (nil? (:id entry))
    [(error :missing-entry-id "Entry must contain an id element"
            [:entries idx :id])]
    (str/blank? (:id entry))
    [(error :empty-entry-id "Entry id must not be empty"
            [:entries idx :id])]
    :else []))

(defn validate-entry-title
  "Entry MUST have exactly one title element."
  [entry idx]
  (when (or (nil? (:title entry)) (str/blank? (:title entry)))
    [(error :missing-entry-title "Entry must contain a title element"
            [:entries idx :title])]))

(defn validate-entry-updated
  "Entry MUST have exactly one updated element with valid RFC 3339 date."
  [entry idx]
  (cond
    (nil? (:updated entry))
    [(error :missing-entry-updated "Entry must contain an updated element"
            [:entries idx :updated])]
    (nil? (parse-datetime (:updated entry)))
    [(error :invalid-entry-updated
            (str "Entry updated must be valid RFC 3339 datetime: " (:updated entry))
            [:entries idx :updated])]
    :else []))

(defn validate-entry-content-or-link
  "Entry MUST contain content, or at least one link with rel='alternate'."
  [entry idx]
  (let [has-content? (not (nil? (get-in entry [:content :value])))
        has-alternate? (some #(#{"alternate" nil} (:rel %)) (:links entry))]
    (when-not (or has-content? has-alternate?)
      [(error :missing-content-or-link
              "Entry must have content or alternate link"
              [:entries idx])])))

(defn validate-entry
  "Validate a single entry. Returns a sequence of errors/warnings."
  [entry idx]
  (concat
   (validate-entry-id entry idx)
   (validate-entry-title entry idx)
   (validate-entry-updated entry idx)
   (validate-entry-content-or-link entry idx)))

(defn validate-feed-structure
  "Validate feed structure per RFC 4287. Returns {:errors [...] :warnings [...]}."
  [feed]
  (let [feed-issues (concat
                     (validate-feed-id feed)
                     (validate-feed-title feed)
                     (validate-feed-updated feed)
                     (validate-feed-author feed)
                     (validate-feed-freshness feed))
        entry-issues (mapcat (fn [[idx entry]]
                               (validate-entry entry idx))
                             (map-indexed vector (:entries feed)))]
    {:errors (vec (filter #(= :error (:type %)) (concat feed-issues entry-issues)))
     :warnings (vec (filter #(= :warning (:type %)) (concat feed-issues entry-issues)))}))
