(ns atom-validator.opml
  "OPML 2.0 batch import for feed lists.

  OPML (Outline Processor Markup Language) is the standard exchange format
  used by RSS readers (Feedly, NewsBlur, Inoreader) to export and share
  collections of feeds.

  Spec: http://opml.org/spec2.opml

  Outlines in OPML are arbitrarily nested. Feed outlines are identified by
  the presence of an :xmlUrl attribute (the canonical signal per the spec).
  Container outlines (categories) hold child outlines but have no :xmlUrl.

  Parsed shape:
    {:title  \"My Feeds\"
     :feeds  [{:title    \"xkcd\"
               :url      \"https://xkcd.com/atom.xml\"
               :type     \"atom\"
               :html-url \"https://xkcd.com\"
               :category \"Tech\"} ...]}"
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.io StringReader]))

(defn- local-name
  "Extract local name from a potentially namespaced keyword."
  [kw]
  (let [n (name kw)]
    (if (str/includes? n "/")
      (second (str/split n #"/"))
      n)))

(defn- find-children
  "Find child elements by local tag name."
  [element local-tag-name]
  (filter #(and (map? %)
                (= (local-name (:tag %)) local-tag-name))
          (:content element)))

(defn- find-child
  "Find first child element by local tag name."
  [element local-tag-name]
  (first (find-children element local-tag-name)))

(defn- text-content
  "Extract text content from an element."
  [element]
  (when element
    (str/join "" (filter string? (:content element)))))

(defn- outline-children
  "Return only the <outline> child elements of an element."
  [element]
  (find-children element "outline"))

(defn- feed-outline?
  "An outline represents a feed if it has an xmlUrl attribute (OPML 2.0 spec)."
  [outline]
  (some? (get-in outline [:attrs :xmlUrl])))

(defn- outline-title
  "Best-effort title for an outline. Prefers :title, falls back to :text."
  [outline]
  (let [attrs (:attrs outline)]
    (or (:title attrs) (:text attrs))))

(defn- ->feed-entry
  "Convert a feed outline element into a feed entry map.
   The category argument is the title of the enclosing container outline (if any)."
  [outline category]
  (let [attrs (:attrs outline)]
    (cond-> {:title    (outline-title outline)
             :url      (:xmlUrl attrs)
             :type     (:type attrs)
             :html-url (:htmlUrl attrs)}
      category (assoc :category category))))

(defn- walk-outlines
  "Walk outline tree and collect feed entries.
   Container outlines (no xmlUrl) push their title as the current category for
   their children. Nested categories overwrite the parent category - we keep
   the most-specific category name, matching the behavior of major RSS readers."
  [outlines category]
  (reduce
    (fn [acc outline]
      (if (feed-outline? outline)
        (conj acc (->feed-entry outline category))
        ;; Container outline - recurse using its title as the category for children
        (let [child-category (or (outline-title outline) category)]
          (into acc (walk-outlines (outline-children outline) child-category)))))
    []
    outlines))

(defn parse-opml
  "Parse an OPML 2.0 document from a string, reader, or input stream.

   Returns a map:
     {:title \"Feed list title\"
      :feeds [{:title :url :type :html-url :category} ...]}

   Handles arbitrarily nested category outlines. A feed is recognised by the
   presence of an xmlUrl attribute (per the OPML 2.0 spec). Container outlines
   become :category labels on their descendant feeds."
  [source]
  (let [xml-source (if (string? source)
                     (StringReader. source)
                     source)
        root       (xml/parse xml-source)
        head       (find-child root "head")
        body       (find-child root "body")
        title      (text-content (find-child head "title"))
        outlines   (outline-children body)]
    {:title title
     :feeds (walk-outlines outlines nil)}))

(defn extract-feed-urls
  "Return a vector of feed URLs from a parsed OPML map (in document order)."
  [opml]
  (->> opml :feeds (mapv :url)))

(defn- safe-validate
  "Run validator against url-or-content and return a result map.
   Captures exceptions so a single bad feed cannot abort batch validation."
  [validator url-or-content url]
  (try
    (let [result (validator url-or-content)]
      {:url     url
       :valid?  (boolean (:valid? result))
       :errors  (vec (:errors result))
       :warnings (vec (:warnings result))})
    (catch Exception e
      {:url    url
       :valid? false
       :errors [{:type :error
                 :code :fetch-or-parse-failed
                 :message (.getMessage e)
                 :path [:opml :feed url]}]
       :warnings []})))

(defn validate-opml-feeds
  "Batch validate every feed referenced by a parsed OPML map.

   Arguments:
     opml    - parsed OPML map (output of parse-opml)

   Options:
     :fetch     - (fn [url] -> feed-string-or-input-stream). REQUIRED for
                  network fetching. The function is responsible for HTTP I/O;
                  the library deliberately does not depend on any HTTP client.
                  Example with babashka:
                    {:fetch #(:body (babashka.http-client/get %))}
     :validate  - (fn [feed-source] -> result-map). Defaults to
                  atom-validator.core/validate-feed. Must return a map with
                  :valid? :errors :warnings.
     :parallel? - run fetches concurrently with pmap (default false).

   Returns:
     {:total    10
      :valid    8
      :invalid  2
      :errors   [...flattened errors...]
      :results  [{:url \"...\" :valid? bool :errors [...] :warnings [...]} ...]}"
  ([opml] (validate-opml-feeds opml {}))
  ([opml {:keys [fetch validate parallel?]
          :or {parallel? false}}]
   (when-not fetch
     (throw (ex-info "validate-opml-feeds requires a :fetch function"
                     {:opts-given (keys opml)})))
   (let [validator (or validate
                       ;; Resolve at call time to avoid a hard compile-time
                       ;; dependency on atom-validator.core (cycle-safe).
                       (requiring-resolve 'atom-validator.core/validate-feed))
         urls      (extract-feed-urls opml)
         do-one    (fn [url]
                     (let [content (try (fetch url)
                                        (catch Exception e
                                          (ex-info (.getMessage e) {:url url} e)))]
                       (if (instance? Throwable content)
                         {:url url
                          :valid? false
                          :errors [{:type :error
                                    :code :fetch-failed
                                    :message (ex-message content)
                                    :path [:opml :feed url]}]
                          :warnings []}
                         (safe-validate validator content url))))
         mapper    (if parallel? pmap map)
         results   (vec (mapper do-one urls))
         valid     (count (filter :valid? results))
         invalid   (- (count results) valid)
         errors    (vec (mapcat :errors (remove :valid? results)))]
     {:total   (count results)
      :valid   valid
      :invalid invalid
      :errors  errors
      :results results})))
