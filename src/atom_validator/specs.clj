(ns atom-validator.specs
  "Data specs for atom-validator (https://clojure.org/guides/spec).

  Covers the parsed Atom feed/entry maps (RFC 4287), RSS 2.0 channels and
  items, JSON Feed 1.1 feeds and items, OPML feed lists, validation issues and
  the {:valid? :errors :warnings} result contract. Function specs (s/fdef) live
  next to each defn in the atom-validator.* namespaces.

  The generators build realistic feeds and render them to XML/JSON documents,
  so stest/check drives the validators with well-formed input."
  (:require [atom-validator.batch :as-alias batch]
            [atom-validator.batch-entry :as-alias batch-entry]
            [atom-validator.batch-opts :as-alias batch-opts]
            [atom-validator.category :as-alias category]
            [atom-validator.content :as-alias content]
            [atom-validator.enclosure :as-alias enclosure]
            [atom-validator.entry :as-alias entry]
            [atom-validator.feed :as-alias feed]
            [atom-validator.feed-list :as-alias feed-list]
            [atom-validator.feed-list-entry :as-alias feed-list-entry]
            [atom-validator.fetch :as-alias fetch]
            [atom-validator.guid :as-alias guid]
            [atom-validator.http-meta :as-alias http-meta]
            [atom-validator.issue :as-alias issue]
            [atom-validator.json-author :as-alias json-author]
            [atom-validator.json-feed :as-alias json-feed]
            [atom-validator.json-item :as-alias json-item]
            [atom-validator.link :as-alias link]
            [atom-validator.person :as-alias person]
            [atom-validator.result :as-alias result]
            [atom-validator.rss-feed :as-alias rss-feed]
            [atom-validator.rss-item :as-alias rss-item]
            [atom-validator.title-day :as-alias title-day]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [lambdaisland.uri :as uri])
  (:import [lambdaisland.uri URI]
           [org.joda.time DateTime DateTimeZone]))

;; --- Generators: text, dates, URLs, ids ---

(def ^:private title-words
  ["Morning" "Brief" "Weekly" "Digest" "Release" "Notes" "Clojure" "Feed" "News"
   "Update" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"
   "Tues" "Thu" "Fri"])

;; Generators are built by fns, not held in vars: building one loads
;; test.check, which is only on the :dev/:test classpath.
(defn- gen-text []
  (gen/one-of [(gen/fmap #(str/join " " %) (gen/vector (gen/elements title-words) 1 4))
               (gen/string-alphanumeric)]))

(defn- gen-rfc3339 []
  (gen/fmap (fn [[y mo d h mi sec frac offset]]
              (format "%04d-%02d-%02dT%02d:%02d:%02d%s%s" y mo d h mi sec frac offset))
            (gen/tuple (gen/choose 2020 2030) (gen/choose 1 12) (gen/choose 1 28)
                       (gen/choose 0 23) (gen/choose 0 59) (gen/choose 0 59)
                       (gen/elements ["" ".5" ".123"])
                       (gen/elements ["Z" "+00:00" "+05:30" "-08:00"]))))

(defn- gen-rfc822 []
  (gen/fmap (fn [[dow d mon y h mi sec zone]]
              (format "%s, %02d %s %04d %02d:%02d:%02d %s" dow d mon y h mi sec zone))
            (gen/tuple (gen/elements ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"])
                       (gen/choose 1 28)
                       (gen/elements ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
                                      "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])
                       (gen/choose 2020 2030) (gen/choose 0 23) (gen/choose 0 59)
                       (gen/choose 0 59)
                       (gen/elements ["+0000" "-0500" "GMT" "EST"]))))

(def ^:private malformed-dates
  ["" "yesterday" "2026-06-19" "2026-13-45T25:61:00Z" "19/06/2026"])

(defn- gen-url []
  (gen/frequency
   [[6 (gen/fmap (fn [[host path]] (str "https://" host "/" path))
                 (gen/tuple (gen/elements ["example.com" "blog.example.org" "news.io"
                                           "feeds.test.net"])
                            (gen/string-alphanumeric)))]
    [1 (gen/elements ["https://wal.shsite/events/" "https://example.compage/x/"
                      "ftp://example.com/feed.xml" "http://localhost/feed"
                      "/relative/path" "not a url" ""])]]))

(defn- gen-id []
  (gen/one-of [(gen/fmap #(str "urn:uuid:" %) (gen/uuid))
               (gen/elements ["tag:example.com,2026:entry-1"
                              "tag:github.com,2008:Repository/1"])
               (gen-url)]))

;; --- Scalars ---

(s/def ::text (s/with-gen string? gen-text))
(s/def ::url-string (s/with-gen string? gen-url))
(s/def ::id-string (s/with-gen string? gen-id))

;; RFC 3339 for Atom and JSON Feed; the validators also see malformed values.
(s/def ::date-string
  (s/with-gen string?
    #(gen/frequency [[8 (gen-rfc3339)] [1 (gen/elements malformed-dates)]])))

;; RFC 822 for RSS 2.0.
(s/def ::rfc822-string
  (s/with-gen string?
    #(gen/frequency [[8 (gen-rfc822)] [1 (gen/elements malformed-dates)]])))

(s/def ::datetime
  (s/with-gen #(instance? DateTime %)
    #(gen/fmap (fn [ms] (DateTime. (long ms) DateTimeZone/UTC))
               (gen/large-integer* {:min 1577836800000 :max 1924991999000}))))

(s/def ::uri (s/with-gen #(instance? URI %) #(gen/fmap uri/uri (gen-url))))

;; A Content-Type header value, including one with the media type missing.
(s/def ::content-type
  (s/with-gen string?
    #(gen/one-of [(gen/elements ["application/atom+xml" "application/rss+xml; charset=utf-8"
                                 "Application/Feed+JSON" "text/xml;charset=ISO-8859-1"
                                 "text/html" "application/octet-stream" ""
                                 "; charset=utf-8"])
                  (gen/string-alphanumeric)])))

(def weekday-names
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(defn weekday-name
  "Full English name of an ISO day-of-week number (1 = Monday)."
  [n]
  (get weekday-names (dec n)))

(s/def ::title-day/day-name string?)
(s/def ::title-day/day-num (s/int-in 1 8))
(s/def ::title-day (s/keys :req-un [::title-day/day-name ::title-day/day-num]))

;; --- Atom (RFC 4287): the maps atom-validator.parser produces ---

(s/def ::link/href (s/nilable ::url-string))
(s/def ::link/rel
  (s/nilable (s/with-gen string? #(gen/elements ["alternate" "self" "related" "enclosure" "via"]))))
(s/def ::link/type
  (s/nilable (s/with-gen string? #(gen/elements ["text/html" "application/atom+xml" "audio/mpeg"]))))
(s/def ::link/hreflang (s/nilable (s/with-gen string? #(gen/elements ["en" "en-US" "fr"]))))
(s/def ::link/title (s/nilable ::text))
(s/def ::link/length (s/nilable (s/with-gen string? #(gen/fmap str (gen/choose 0 100000)))))
(s/def ::link
  (s/keys :opt-un [::link/href ::link/rel ::link/type ::link/hreflang ::link/title ::link/length]))

(s/def ::person/name (s/nilable ::text))
(s/def ::person/uri (s/nilable ::url-string))
(s/def ::person/email
  (s/nilable (s/with-gen string? #(gen/elements ["jo@example.com" "feeds@example.org"]))))
(s/def ::person (s/keys :opt-un [::person/name ::person/uri ::person/email]))

(s/def ::category/term (s/nilable ::text))
(s/def ::category/scheme (s/nilable ::url-string))
(s/def ::category/label (s/nilable ::text))
(s/def ::category (s/keys :opt-un [::category/term ::category/scheme ::category/label]))

(s/def ::content/type (s/with-gen string? #(gen/elements ["text" "html" "xhtml" "text/plain"])))
(s/def ::content/src (s/nilable ::url-string))
(s/def ::content/value (s/nilable ::text))
(s/def ::content (s/keys :opt-un [::content/type ::content/src ::content/value]))

(s/def ::people (s/coll-of ::person :kind sequential? :gen-max 2))
(s/def ::links (s/coll-of ::link :kind sequential? :gen-max 3))
(s/def ::categories (s/coll-of ::category :kind sequential? :gen-max 2))

(s/def ::entry/id (s/nilable ::id-string))
(s/def ::entry/title (s/nilable ::text))
(s/def ::entry/updated (s/nilable ::date-string))
(s/def ::entry/published (s/nilable ::date-string))
(s/def ::entry/summary (s/nilable ::text))
(s/def ::entry/content (s/nilable ::content))
(s/def ::entry/authors ::people)
(s/def ::entry/contributors ::people)
(s/def ::entry/links ::links)
(s/def ::entry/categories ::categories)
(s/def ::entry/rights (s/nilable ::text))
;; the raw <source> element (a clojure.data.xml element), kept unparsed
(s/def ::entry/source (s/with-gen (s/nilable map?) #(gen/return nil)))

(s/def ::entry
  (s/keys :opt-un [::entry/id ::entry/title ::entry/updated ::entry/published ::entry/summary
                   ::entry/content ::entry/authors ::entry/contributors ::entry/links
                   ::entry/categories ::entry/rights ::entry/source]))

(s/def ::parsed-entry
  (s/merge ::entry
           (s/keys :req-un [::entry/id ::entry/title ::entry/updated ::entry/content
                            ::entry/authors ::entry/links ::entry/categories])))

(s/def ::feed/id (s/nilable ::id-string))
(s/def ::feed/title (s/nilable ::text))
(s/def ::feed/subtitle (s/nilable ::text))
(s/def ::feed/updated (s/nilable ::date-string))
(s/def ::feed/rights (s/nilable ::text))
(s/def ::feed/icon (s/nilable ::url-string))
(s/def ::feed/logo (s/nilable ::url-string))
(s/def ::feed/generator (s/nilable ::text))
(s/def ::feed/authors ::people)
(s/def ::feed/contributors ::people)
(s/def ::feed/links ::links)
(s/def ::feed/categories ::categories)
(s/def ::feed/entries (s/coll-of ::entry :kind sequential? :gen-max 4))
(s/def ::feed/format #{:atom})

;; Any map with Atom feed fields. The validators accept partial maps and
;; report what is missing, so every key is optional here.
(s/def ::atom-feed
  (s/keys :opt-un [::feed/id ::feed/title ::feed/subtitle ::feed/updated ::feed/rights
                   ::feed/icon ::feed/logo ::feed/generator ::feed/authors
                   ::feed/contributors ::feed/links ::feed/categories ::feed/entries
                   ::feed/format]))

;; What atom-validator.parser/parse-feed returns.
(s/def ::atom-parse
  (s/merge ::atom-feed
           (s/keys :req-un [::feed/id ::feed/title ::feed/updated ::feed/authors
                            ::feed/links ::feed/entries])))

;; What atom-validator.core/parse-feed returns for Atom input.
(s/def ::parsed-atom-feed (s/merge ::atom-parse (s/keys :req-un [::feed/format])))

;; --- RSS 2.0 ---

(s/def ::guid/value (s/nilable ::text))
(s/def ::guid/is-perma-link (s/with-gen string? #(gen/elements ["true" "false"])))
(s/def ::guid (s/keys :opt-un [::guid/value ::guid/is-perma-link]))

(s/def ::enclosure/url (s/nilable ::url-string))
(s/def ::enclosure/length ::link/length)
(s/def ::enclosure/type ::link/type)
(s/def ::enclosure (s/keys :opt-un [::enclosure/url ::enclosure/length ::enclosure/type]))

(s/def ::rss-item/title (s/nilable ::text))
(s/def ::rss-item/link (s/nilable ::url-string))
(s/def ::rss-item/description (s/nilable ::text))
(s/def ::rss-item/author ::person/email)
(s/def ::rss-item/pub-date (s/nilable ::rfc822-string))
(s/def ::rss-item/guid (s/nilable ::guid))
(s/def ::rss-item/enclosure (s/nilable ::enclosure))
(s/def ::rss-item/categories (s/coll-of ::text :kind sequential? :gen-max 2))
(s/def ::rss-item/comments (s/nilable ::url-string))
(s/def ::rss-item/source (s/nilable ::text))
(s/def ::rss-item
  (s/keys :opt-un [::rss-item/title ::rss-item/link ::rss-item/description ::rss-item/author
                   ::rss-item/pub-date ::rss-item/guid ::rss-item/enclosure
                   ::rss-item/categories ::rss-item/comments ::rss-item/source]))

(s/def ::rss-feed/format #{:rss})
(s/def ::rss-feed/version (s/with-gen string? #(gen/elements ["2.0" "0.92"])))
(s/def ::rss-feed/title (s/nilable ::text))
(s/def ::rss-feed/link (s/nilable ::url-string))
(s/def ::rss-feed/description (s/nilable ::text))
(s/def ::rss-feed/language ::link/hreflang)
(s/def ::rss-feed/copyright (s/nilable ::text))
(s/def ::rss-feed/managing-editor ::person/email)
(s/def ::rss-feed/web-master ::person/email)
(s/def ::rss-feed/pub-date (s/nilable ::rfc822-string))
(s/def ::rss-feed/last-build-date (s/nilable ::rfc822-string))
(s/def ::rss-feed/generator (s/nilable ::text))
(s/def ::rss-feed/docs (s/nilable ::url-string))
(s/def ::rss-feed/ttl (s/nilable (s/with-gen string? #(gen/fmap str (gen/choose 1 1440)))))
(s/def ::rss-feed/categories (s/coll-of ::text :kind sequential? :gen-max 2))
(s/def ::rss-feed/items (s/coll-of ::rss-item :kind sequential? :gen-max 4))

;; Any map with RSS channel fields: what the per-rule validators accept.
(s/def ::rss-channel
  (s/keys :opt-un [::rss-feed/format ::rss-feed/version ::rss-feed/title ::rss-feed/link
                   ::rss-feed/description ::rss-feed/language ::rss-feed/copyright
                   ::rss-feed/managing-editor ::rss-feed/web-master ::rss-feed/pub-date
                   ::rss-feed/last-build-date ::rss-feed/generator ::rss-feed/docs
                   ::rss-feed/ttl ::rss-feed/categories ::rss-feed/items]))

;; A channel tagged :format :rss, so core/validate-feed routes it to the RSS rules.
(s/def ::rss-feed (s/merge ::rss-channel (s/keys :req-un [::rss-feed/format])))

(s/def ::parsed-rss-feed
  (s/merge ::rss-feed
           (s/keys :req-un [::rss-feed/version ::rss-feed/title ::rss-feed/link
                            ::rss-feed/description ::rss-feed/items])))

;; --- JSON Feed 1.1 ---

(def json-feed-versions
  ["https://jsonfeed.org/version/1" "https://jsonfeed.org/version/1.1"])

(s/def ::json-author/name (s/nilable ::text))
(s/def ::json-author/url (s/nilable ::url-string))
(s/def ::json-author/avatar (s/nilable ::url-string))
(s/def ::json-author (s/keys :opt-un [::json-author/name ::json-author/url ::json-author/avatar]))
(s/def ::json-authors (s/coll-of ::json-author :kind sequential? :gen-max 2))

(s/def ::json-item/id (s/nilable (s/or :string ::text :number int?)))
(s/def ::json-item/url (s/nilable ::url-string))
(s/def ::json-item/external_url (s/nilable ::url-string))
(s/def ::json-item/title (s/nilable ::text))
(s/def ::json-item/content_html
  (s/nilable (s/with-gen string? #(gen/fmap (fn [t] (str "<p>" t "</p>")) (gen-text)))))
(s/def ::json-item/content_text (s/nilable ::text))
(s/def ::json-item/summary (s/nilable ::text))
(s/def ::json-item/image (s/nilable ::url-string))
(s/def ::json-item/banner_image (s/nilable ::url-string))
(s/def ::json-item/date_published (s/nilable ::date-string))
(s/def ::json-item/date_modified (s/nilable ::date-string))
(s/def ::json-item/authors ::json-authors)
(s/def ::json-item/tags (s/coll-of ::text :kind sequential? :gen-max 3))
(s/def ::json-item
  (s/keys :opt-un [::json-item/id ::json-item/url ::json-item/external_url ::json-item/title
                   ::json-item/content_html ::json-item/content_text ::json-item/summary
                   ::json-item/image ::json-item/banner_image ::json-item/date_published
                   ::json-item/date_modified ::json-item/authors ::json-item/tags]))

(s/def ::json-feed/version
  (s/nilable (s/with-gen string?
               #(gen/elements (conj json-feed-versions "1.1" "https://jsonfeed.org/version/2")))))
(s/def ::json-feed/title (s/nilable ::text))
(s/def ::json-feed/home_page_url (s/nilable ::url-string))
(s/def ::json-feed/feed_url (s/nilable ::url-string))
(s/def ::json-feed/description (s/nilable ::text))
(s/def ::json-feed/icon (s/nilable ::url-string))
(s/def ::json-feed/favicon (s/nilable ::url-string))
(s/def ::json-feed/authors ::json-authors)
(s/def ::json-feed/language ::link/hreflang)
(s/def ::json-feed/expired boolean?)
;; JSON Feed requires an array; the validator reports anything else as :invalid-items.
(s/def ::json-feed/items
  (s/with-gen (s/or :array (s/coll-of ::json-item :kind sequential?)
                    :malformed (complement sequential?))
    #(gen/frequency [[9 (gen/vector (s/gen ::json-item) 0 4)]
                     [1 (gen/elements ["not-an-array" {}])]])))
(s/def ::json-feed/format #{:json-feed})

(s/def ::json-feed
  (s/keys :opt-un [::json-feed/version ::json-feed/title ::json-feed/home_page_url
                   ::json-feed/feed_url ::json-feed/description ::json-feed/icon
                   ::json-feed/favicon ::json-feed/authors ::json-feed/language
                   ::json-feed/expired ::json-feed/items ::json-feed/format]))

;; A JSON Feed tagged :format :json-feed (what core/parse-feed promises).
(s/def ::tagged-json-feed (s/merge ::json-feed (s/keys :req-un [::json-feed/format])))

;; --- OPML feed lists ---

(s/def ::feed-list-entry/title (s/nilable ::text))
(s/def ::feed-list-entry/url ::url-string)
(s/def ::feed-list-entry/type (s/nilable (s/with-gen string? #(gen/elements ["rss" "atom" "json"]))))
(s/def ::feed-list-entry/html-url (s/nilable ::url-string))
(s/def ::feed-list-entry/category ::text)
(s/def ::feed-list-entry
  (s/keys :req-un [::feed-list-entry/url]
          :opt-un [::feed-list-entry/title ::feed-list-entry/type ::feed-list-entry/html-url
                   ::feed-list-entry/category]))

(s/def ::feed-list/title (s/nilable ::text))
(s/def ::feed-list/feeds (s/coll-of ::feed-list-entry :kind vector? :gen-max 5))
(s/def ::feed-list (s/keys :req-un [::feed-list/feeds] :opt-un [::feed-list/title]))
(s/def ::parsed-feed-list (s/merge ::feed-list (s/keys :req-un [::feed-list/title])))

;; --- Rendering (generators and round-trip tests) ---

(defn- text-el [tag v] (when (some? v) [tag {} v]))

(defn- attrs-of [m ks]
  (into {} (keep (fn [k] (when-some [v (get m k)] [k v]))) ks))

(defn- person-el [tag p]
  (into [tag {}] (keep (fn [k] (text-el k (get p k)))) [:name :uri :email]))

(defn- link-el [l] [:link (attrs-of l [:href :rel :type :hreflang :title :length])])

(defn- category-el [c] [:category (attrs-of c [:term :scheme :label])])

(defn- entry-sexp [e]
  (-> [:entry {}]
      (into (keep (fn [k] (text-el k (get e k)))) [:id :title :updated :published :summary :rights])
      (cond-> (:content e) (conj [:content (attrs-of (:content e) [:type :src])
                                  (or (get-in e [:content :value]) "")]))
      (into (map #(person-el :author %)) (:authors e))
      (into (map #(person-el :contributor %)) (:contributors e))
      (into (map link-el) (:links e))
      (into (map category-el) (:categories e))))

(defn atom-feed->xml
  "Render an Atom feed map as an XML document (the inverse of parser/parse-feed)."
  [feed]
  (xml/emit-str
   (xml/sexp-as-element
    (-> [:feed {}]
        (into (keep (fn [k] (text-el k (get feed k))))
              [:id :title :subtitle :updated :rights :icon :logo :generator])
        (into (map #(person-el :author %)) (:authors feed))
        (into (map #(person-el :contributor %)) (:contributors feed))
        (into (map link-el) (:links feed))
        (into (map category-el) (:categories feed))
        (into (map entry-sexp) (:entries feed))))))

(defn- rss-item-sexp [item]
  (-> [:item {}]
      (into (keep (fn [[k tag]] (text-el tag (get item k))))
            [[:title :title] [:link :link] [:description :description] [:author :author]
             [:pub-date :pubDate] [:comments :comments] [:source :source]])
      (cond-> (:guid item) (conj [:guid (attrs-of {:isPermaLink (get-in item [:guid :is-perma-link])}
                                                  [:isPermaLink])
                                  (or (get-in item [:guid :value]) "")]))
      (cond-> (:enclosure item) (conj [:enclosure (attrs-of (:enclosure item) [:url :length :type])]))
      (into (map (fn [c] [:category {} c])) (:categories item))))

(defn rss-feed->xml
  "Render an RSS channel map as an RSS 2.0 document (the inverse of rss/parse-rss-feed)."
  [feed]
  (xml/emit-str
   (xml/sexp-as-element
    [:rss {:version (or (:version feed) "2.0")}
     (-> [:channel {}]
         (into (keep (fn [[k tag]] (text-el tag (get feed k))))
               [[:title :title] [:link :link] [:description :description]
                [:language :language] [:copyright :copyright]
                [:managing-editor :managingEditor] [:web-master :webMaster]
                [:pub-date :pubDate] [:last-build-date :lastBuildDate]
                [:generator :generator] [:docs :docs] [:ttl :ttl]])
         (into (map (fn [c] [:category {} c])) (:categories feed))
         (into (map rss-item-sexp) (:items feed)))])))

(defn json-feed->json
  "Render a JSON Feed map as a JSON document."
  [feed]
  (json/write-str (dissoc feed :format)))

(defn feed-list->opml
  "Render a feed-list map as an OPML 2.0 document (the inverse of opml/parse-opml).
  A feed with a :category is wrapped in a container outline of that name."
  [{:keys [title feeds]}]
  (xml/emit-str
   (xml/sexp-as-element
    [:opml {:version "2.0"}
     (into [:head {}] (keep identity) [(text-el :title title)])
     (into [:body {}]
           (map (fn [{:keys [category] :as f}]
                  (let [outline [:outline (attrs-of {:text (:title f) :title (:title f)
                                                     :type (:type f) :xmlUrl (:url f)
                                                     :htmlUrl (:html-url f)}
                                                    [:text :title :type :xmlUrl :htmlUrl])]]
                    (if category [:outline {:text category} outline] outline))))
           feeds)])))

;; --- Documents and sources ---

(defn- reader? [x] (instance? java.io.Reader x))
(defn- stream? [x] (instance? java.io.InputStream x))

(s/def ::xml-document
  (s/with-gen (s/and string? #(str/starts-with? (str/triml %) "<"))
    #(gen/one-of [(gen/fmap atom-feed->xml (s/gen ::atom-feed))
                  (gen/fmap rss-feed->xml (s/gen ::rss-channel))])))
(s/def ::atom-document (s/with-gen ::xml-document #(gen/fmap atom-feed->xml (s/gen ::atom-feed))))
(s/def ::rss-document (s/with-gen ::xml-document #(gen/fmap rss-feed->xml (s/gen ::rss-channel))))
(s/def ::opml-document (s/with-gen ::xml-document #(gen/fmap feed-list->opml (s/gen ::feed-list))))
(s/def ::json-document
  (s/with-gen (s/and string? #(str/starts-with? (str/triml %) "{"))
    #(gen/fmap json-feed->json (s/gen ::json-feed))))
(s/def ::feed-document (s/or :xml ::xml-document :json ::json-document))

(defn- source-of
  "A document string, Reader or InputStream; generates documents only."
  [doc-spec]
  (s/with-gen (s/or :document doc-spec :reader reader? :stream stream?)
    #(s/gen doc-spec)))

(s/def ::xml-source (source-of ::xml-document))
(s/def ::atom-source (source-of ::atom-document))
(s/def ::rss-source (source-of ::rss-document))
(s/def ::opml-source (source-of ::opml-document))
(s/def ::document-source (source-of ::feed-document))

(s/def ::json-source
  (s/with-gen (s/or :map map? :document ::json-document :reader reader?)
    #(gen/one-of [(s/gen ::json-feed) (s/gen ::json-document)])))

;; Anything format detection may be handed, including non-feed text.
(s/def ::detect-source
  (s/with-gen (s/or :string string? :reader reader? :stream stream?)
    #(gen/one-of [(s/gen ::feed-document) (gen/string-alphanumeric)])))

;; A parsed Atom element (clojure.data.xml), e.g. an <entry> or <item>.
(s/def ::xml-element
  (s/with-gen (s/and map? #(keyword? (:tag %)))
    #(gen/fmap (comp xml/parse-str xml/emit-str xml/sexp-as-element)
               (gen/one-of [(gen/fmap entry-sexp (s/gen ::entry))
                            (gen/fmap rss-item-sexp (s/gen ::rss-item))]))))

(s/def ::http-url (s/and string? #(re-find #"^https?://" %)))

;; What core/validate-feed accepts. URLs are fetched over HTTP, so generated
;; input sticks to maps and documents.
(s/def ::feed-input
  (s/with-gen (s/or :rss ::rss-feed :json ::tagged-json-feed :atom ::atom-feed
                    :url ::http-url :source ::document-source)
    #(gen/one-of [(s/gen ::atom-feed) (s/gen ::rss-feed) (s/gen ::tagged-json-feed)
                  (s/gen ::feed-document)])))

(s/def ::detected-format #{:atom :rss :json-feed :unknown})

(s/def ::parsed-feed
  (s/or :atom ::parsed-atom-feed :rss ::parsed-rss-feed :json-feed ::tagged-json-feed))

;; --- Options ---

(s/def ::semantic? boolean?)
(s/def ::strict? boolean?)
(s/def ::fetch? boolean?)
(s/def ::format #{:atom :rss :json-feed})
(s/def ::timeout-seconds pos-int?)
(s/def ::max-redirects nat-int?)
(s/def ::validate-content-type? boolean?)
(s/def ::head-check? boolean?)
(s/def ::allow-iri? boolean?)

(s/def ::validate-opts
  (s/with-gen (s/keys :opt-un [::semantic? ::strict? ::format ::fetch? ::timeout-seconds
                               ::max-redirects ::validate-content-type? ::head-check?])
    ;; :format must match the document, and :fetch? only matters for URLs,
    ;; so generated options stick to the rule switches
    #(s/gen (s/keys :opt-un [::semantic? ::strict?]))))

(s/def ::parse-opts (s/with-gen (s/keys :opt-un [::format]) #(gen/return {})))

(s/def ::batch-opts/fetch ifn?)
(s/def ::batch-opts/validate ifn?)
(s/def ::batch-opts/parallel? boolean?)
(s/def ::batch-opts
  (s/keys :opt-un [::batch-opts/fetch ::batch-opts/validate ::batch-opts/parallel?]))

;; --- Issues and results ---

(def issue-codes
  #{:missing-feed-id :empty-feed-id :missing-feed-title :missing-feed-updated
    :invalid-feed-updated :missing-authors :stale-feed-updated :missing-entry-id
    :empty-entry-id :missing-entry-title :missing-entry-updated :invalid-entry-updated
    :missing-content-or-link :day-of-week-mismatch :invalid-url :invalid-url-scheme
    :invalid-url-host :missing-channel-title :missing-channel-link
    :missing-channel-description :invalid-pubdate :missing-item-content :invalid-guid
    :duplicate-guid :missing-version :invalid-version :missing-title :missing-items
    :invalid-items :author-missing-name :missing-item-id :invalid-date :http-error
    :http-timeout :invalid-content-type :max-redirects-exceeded :fetch-failed
    :fetch-or-parse-failed})

;; nonconforming, so :fn predicates see paths as plain vectors
(s/def ::path-segment
  (s/nonconforming (s/or :key keyword? :index nat-int? :url (s/nilable string?))))
(s/def ::path (s/coll-of ::path-segment :kind vector?))

(s/def ::issue/type #{:error :warning})
(s/def ::issue/code (s/with-gen keyword? #(gen/elements (sort issue-codes))))
(s/def ::issue/message string?)
(s/def ::issue/path ::path)
(s/def ::issue/context ::path)
(s/def ::issue/url (s/nilable string?))
(s/def ::issue/expected string?)
(s/def ::issue/found string?)
(s/def ::issue
  (s/keys :req-un [::issue/type ::issue/code ::issue/message]
          :opt-un [::issue/path ::issue/context ::issue/url ::issue/expected ::issue/found]))

(s/def ::error (s/and ::issue #(= :error (:type %))))
(s/def ::warning (s/and ::issue #(= :warning (:type %))))
(s/def ::issues (s/nilable (s/coll-of ::issue :kind sequential?)))
(s/def ::errors (s/coll-of ::error :kind vector?))
(s/def ::warnings (s/coll-of ::warning :kind vector?))
(s/def ::issue-buckets (s/keys :req-un [::errors ::warnings]))

(s/def ::valid? boolean?)
(s/def ::http-meta/url (s/nilable string?))
(s/def ::http-meta/status (s/nilable (s/int-in 100 600)))
(s/def ::http-meta/content-type (s/nilable string?))
(s/def ::http-meta/redirects (s/nilable (s/coll-of string? :kind vector?)))
(s/def ::result/http
  (s/keys :req-un [::http-meta/url ::http-meta/status ::http-meta/content-type
                   ::http-meta/redirects]))
(s/def ::result/feed map?)

(s/def ::entry-result (s/keys :req-un [::valid? ::errors ::warnings]))
(s/def ::result (s/merge ::entry-result (s/keys :opt-un [::result/feed ::result/http])))
(s/def ::feed-result (s/merge ::entry-result (s/keys :req-un [::result/feed])))

(s/def ::fetch/ok? boolean?)
(s/def ::fetch/url (s/nilable string?))
(s/def ::fetch/status (s/int-in 100 600))
(s/def ::fetch/content-type (s/nilable string?))
(s/def ::fetch/body string?)
(s/def ::fetch/redirects (s/coll-of string? :kind vector?))
(s/def ::fetch-result
  (s/keys :req-un [::fetch/ok? ::fetch/url ::fetch/redirects ::errors]
          :opt-un [::fetch/status ::fetch/content-type ::fetch/body]))

(s/def ::batch-entry/url (s/nilable string?))
(s/def ::batch-entry (s/keys :req-un [::batch-entry/url ::valid? ::errors ::warnings]))
(s/def ::batch/total nat-int?)
(s/def ::batch/valid nat-int?)
(s/def ::batch/invalid nat-int?)
(s/def ::batch/results (s/coll-of ::batch-entry :kind vector?))
(s/def ::batch-result
  (s/keys :req-un [::batch/total ::batch/valid ::batch/invalid ::errors ::batch/results]))

;; --- Shared fdef pieces ---

(s/def ::idx nat-int?)
(s/def ::feed-rule-args (s/cat :feed ::atom-feed))
(s/def ::entry-rule-args (s/cat :entry ::entry :idx ::idx))
(s/def ::rss-channel-args (s/cat :feed ::rss-channel))
(s/def ::rss-item-args (s/cat :item ::rss-item :idx ::idx))
(s/def ::json-feed-args (s/cat :feed ::json-feed))
(s/def ::json-item-args (s/cat :item ::json-item :idx ::idx))

(defn- issues-under [k]
  (fn [{{:keys [idx]} :args ret :ret}]
    (every? (fn [{:keys [path]}] (= [k idx] (take 2 path))) ret)))

(def issues-under-entry
  "s/fdef :fn for per-entry rules: every issue points into [:entries idx]."
  (issues-under :entries))

(def issues-under-item
  "s/fdef :fn for per-item rules: every issue points into [:items idx]."
  (issues-under :items))

(defn result-consistent?
  "s/fdef :fn for validators: :valid? is true iff there are no errors (and, with
  :strict?, no warnings either)."
  [{{:keys [opts]} :args ret :ret}]
  (= (:valid? ret)
     (empty? (cond-> (:errors ret) (:strict? opts) (concat (:warnings ret))))))
