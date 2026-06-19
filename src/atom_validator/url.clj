(ns atom-validator.url
  "URL/IRI validation for Atom feed elements.
   Uses lambdaisland/uri for parsing."
  (:require [lambdaisland.uri :as uri]
            [clojure.string :as str]))

(defn parse-url
  "Parse a URL string. Returns the parsed URI or nil if invalid."
  [url-str]
  (when (and url-str (string? url-str) (not (str/blank? url-str)))
    (try
      (uri/uri url-str)
      (catch Exception _
        nil))))

(defn urn?
  "Check if a URL is a URN (e.g., urn:uuid:...)."
  [url-str]
  (when url-str
    (re-matches #"^urn:[a-zA-Z0-9][a-zA-Z0-9-]*:.*$" url-str)))

(defn tag-uri?
  "Check if a URL is a tag: URI per RFC 4151 (e.g., tag:github.com,2008:...).
   Format: tag:<authority>,<date>:<specific>"
  [url-str]
  (when url-str
    (re-matches #"^tag:[a-zA-Z0-9.-]+,\d{4}(-\d{2}(-\d{2})?)?:.*$" url-str)))

(defn valid-iri-for-id?
  "Check if string is a valid IRI for atom:id per RFC 4287.
   Allows: URNs (urn:uuid:...), tag URIs (tag:...), and absolute URLs."
  [url-str]
  (or (urn? url-str)
      (tag-uri? url-str)))

(defn valid-scheme?
  "Check if URL has a valid scheme (http, https)."
  [parsed]
  (when parsed
    (contains? #{"http" "https"} (:scheme parsed))))

(def suspicious-tld-suffixes
  "Suffixes that suggest a typo where someone merged host and path."
  #{"site" "page" "path" "events" "api" "app"})

(defn suspicious-tld?
  "Check if TLD looks like a typo (e.g., 'shsite' contains 'site')."
  [tld]
  (when tld
    (some (fn [suffix]
            (and (> (count tld) (count suffix))
                 (str/ends-with? tld suffix)))
          suspicious-tld-suffixes)))

(defn valid-host?
  "Check if URL has a valid host."
  [parsed]
  (when-let [host (:host parsed)]
    (let [hostname-pattern #"^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$"
          parts (str/split host #"\.")
          tld (last parts)]
      (and (not (str/blank? host))
           (re-matches hostname-pattern host)
           (>= (count parts) 2)
           (>= (count tld) 2)
           (not (suspicious-tld? tld))))))

(defn absolute-url?
  "Check if URL is absolute (has scheme and host)."
  [parsed]
  (and (valid-scheme? parsed)
       (valid-host? parsed)))

(defn validate-url
  "Validate a URL string. Returns error map or nil if valid.
   URN identifiers (urn:uuid:...) and tag: URIs are valid for atom:id per RFC 4287."
  [url-str context & {:keys [allow-iri?] :or {allow-iri? false}}]
  (cond
    ;; Allow URNs and tag: URIs for atom:id elements
    (and allow-iri? (valid-iri-for-id? url-str))
    nil

    :else
    (let [parsed (parse-url url-str)]
      (cond
        (nil? parsed)
        {:type :error
         :code :invalid-url
         :message (str "Invalid URL syntax: " url-str)
         :context context
         :url url-str}

        (not (valid-scheme? parsed))
        {:type :error
         :code :invalid-url-scheme
         :message (str "URL must use http or https scheme: " url-str)
         :context context
         :url url-str}

        (not (valid-host? parsed))
        {:type :error
         :code :invalid-url-host
         :message (str "URL has invalid host: " url-str)
         :context context
         :url url-str}

        :else nil))))

(defn validate-entry-urls
  "Validate all URLs in an entry (id, links).
   Note: atom:id can be a URN or tag: URI per RFC 4287/RFC 4151."
  [entry idx]
  (let [id-error (when (:id entry)
                   (validate-url (:id entry) [:entries idx :id] :allow-iri? true))
        link-errors (keep-indexed
                     (fn [link-idx link]
                       (when (:href link)
                         (when-let [err (validate-url (:href link) [:entries idx :links link-idx])]
                           (assoc err :path [:entries idx :links link-idx :href]))))
                     (:links entry))]
    (filterv some? (cons (when id-error
                           (assoc id-error :path [:entries idx :id]))
                         link-errors))))

(defn validate-feed-urls
  "Validate all URLs in a feed and its entries.
   Returns {:errors [...] :warnings [...]}."
  [feed]
  (let [feed-link-errors (keep-indexed
                          (fn [idx link]
                            (when (:href link)
                              (when-let [err (validate-url (:href link) [:links idx])]
                                (assoc err :path [:links idx :href]))))
                          (:links feed))
        entry-errors (mapcat (fn [[idx entry]]
                               (validate-entry-urls entry idx))
                             (map-indexed vector (:entries feed)))]
    {:errors (vec (concat feed-link-errors entry-errors))
     :warnings []}))
