(ns atom-validator.generators
  "test.check generators for Atom feed validation testing."
  (:require [clojure.test.check.generators :as gen]
            [clojure.string :as str]))

;; Date/Time Generators

(def gen-year
  "Generate a year between 2020 and 2030."
  (gen/choose 2020 2030))

(def gen-month
  "Generate a month 1-12."
  (gen/choose 1 12))

(def gen-day
  "Generate a day 1-28 (safe for all months)."
  (gen/choose 1 28))

(def gen-hour (gen/choose 0 23))
(def gen-minute (gen/choose 0 59))
(def gen-second (gen/choose 0 59))

(def gen-rfc3339-date
  "Generator for valid RFC 3339 timestamps."
  (gen/fmap
   (fn [[year month day hour minute second]]
     (format "%04d-%02d-%02dT%02d:%02d:%02dZ"
             year month day hour minute second))
   (gen/tuple gen-year gen-month gen-day gen-hour gen-minute gen-second)))

;; Day of week generators

(def day-names
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(def gen-day-name
  "Generate a day name."
  (gen/elements day-names))

;; URL Generators

(def gen-valid-domain
  "Generate valid domain names."
  (gen/elements ["example.com" "test.org" "blog.io" "news.net" "feed.edu"]))

(def gen-path-segment
  "Generate a URL path segment."
  (gen/fmap
   (fn [chars] (apply str chars))
   (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz0123456789-")) 3 10)))

(def gen-valid-url
  "Generate a valid absolute URL."
  (gen/fmap
   (fn [[domain path]]
     (str "https://" domain "/" path "/"))
   (gen/tuple gen-valid-domain gen-path-segment)))

(def gen-invalid-url-host
  "Generate URLs with invalid hostnames."
  (gen/elements
   ["https://wal.shsite/path/"      ; Invalid TLD pattern (shsite ends in 'site')
    "https://example.compage/x/"    ; Invalid TLD pattern (compage ends in 'page')
    "https://test.orgpath/y/"       ; Invalid TLD pattern (orgpath ends in 'path')
    "https://api.ioapp/z/"]))       ; Invalid TLD pattern (ioapp ends in 'app')

;; Entry Generators

(def gen-uuid
  "Generate a UUID string."
  (gen/fmap
   (fn [_] (str "urn:uuid:" (java.util.UUID/randomUUID)))
   gen/nat))

(def gen-simple-title
  "Generate a simple title without day-of-week."
  (gen/fmap
   (fn [words] (str/join " " words))
   (gen/vector (gen/elements ["Article" "Post" "News" "Update" "Release" "Guide"]) 1 3)))

(def gen-valid-atom-entry
  "Generator for valid Atom entries."
  (gen/fmap
   (fn [[id title updated url]]
     {:id id
      :title title
      :updated updated
      :links [{:href url :rel "alternate"}]})
   (gen/tuple gen-uuid gen-simple-title gen-rfc3339-date gen-valid-url)))

;; Generators for specific validation scenarios

(defn gen-entry-with-mismatched-day
  "Generator for entries where title day-of-week doesn't match updated date.
   This tests Issue #1."
  []
  (gen/fmap
   (fn [[id day-name _date url]]
     ;; We use a fixed date (2026-06-19 = Friday) and wrong day names
     {:id id
      :title (str "Morning Brief: " day-name ", June 19")
      :updated "2026-06-19T00:00:00Z"
      :links [{:href url :rel "alternate"}]})
   (gen/tuple
    gen-uuid
    (gen/elements ["Monday" "Tuesday" "Wednesday" "Thursday" "Saturday" "Sunday"])
    gen-rfc3339-date
    gen-valid-url)))

(defn gen-entry-with-correct-day
  "Generator for entries where title day-of-week matches updated date."
  []
  (gen/fmap
   (fn [[id url]]
     ;; 2026-06-19 is a Friday
     {:id id
      :title "Morning Brief: Friday, June 19"
      :updated "2026-06-19T00:00:00Z"
      :links [{:href url :rel "alternate"}]})
   (gen/tuple gen-uuid gen-valid-url)))

(defn gen-entry-with-invalid-url
  "Generator for entries with invalid URLs.
   This tests Issue #3."
  []
  (gen/fmap
   (fn [[_id title date bad-url]]
     {:id bad-url  ; ID is also a URL in many feeds
      :title title
      :updated date
      :links [{:href bad-url :rel "alternate"}]})
   (gen/tuple gen-uuid gen-simple-title gen-rfc3339-date gen-invalid-url-host)))

(def gen-valid-atom-feed
  "Generator for valid Atom feeds.
   Feed updated is set to the newest entry's updated timestamp.
   RFC 3339 timestamps are lexicographically sortable."
  (gen/fmap
   (fn [[id title entries]]
     (let [max-entry-updated (reduce (fn [a b] (if (pos? (compare a b)) a b))
                                     (map :updated entries))]
       {:id id
        :title title
        :updated max-entry-updated
        :entries entries}))
   (gen/tuple gen-uuid gen-simple-title
              (gen/vector gen-valid-atom-entry 1 5))))

(defn gen-feed-with-stale-updated
  "Generator for feeds where updated < max(entry updated).
   This tests Issue #2."
  []
  (gen/fmap
   (fn [[id title]]
     ;; Feed updated is older than entry updated
     {:id id
      :title title
      :updated "2026-06-14T00:00:00Z"
      :entries [{:id "urn:uuid:123"
                 :title "Newer Entry"
                 :updated "2026-06-19T00:00:00Z"
                 :links [{:href "https://example.com/1/" :rel "alternate"}]}
                {:id "urn:uuid:456"
                 :title "Older Entry"
                 :updated "2026-06-18T00:00:00Z"
                 :links [{:href "https://example.com/2/" :rel "alternate"}]}]})
   (gen/tuple gen-uuid gen-simple-title)))

;; Unused gen-rfc3339-date reference removed from gen-entry-with-mismatched-day
