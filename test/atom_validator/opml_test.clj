(ns atom-validator.opml-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [atom-validator.opml :as opml]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def sample-opml-path "test/fixtures/sample.opml")

(defn- read-sample []
  (slurp (io/file sample-opml-path)))

(def minimal-opml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <opml version=\"2.0\">
     <head><title>Minimal</title></head>
     <body>
       <outline type=\"atom\" text=\"xkcd\" xmlUrl=\"https://xkcd.com/atom.xml\"/>
     </body>
   </opml>")

(def empty-body-opml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <opml version=\"2.0\">
     <head><title>Empty</title></head>
     <body></body>
   </opml>")

(def deeply-nested-opml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <opml version=\"2.0\">
     <head><title>Nested</title></head>
     <body>
       <outline text=\"Top\" title=\"Top\">
         <outline text=\"Mid\" title=\"Mid\">
           <outline text=\"Inner\" title=\"Inner\">
             <outline type=\"atom\" text=\"deep\"
                      xmlUrl=\"https://example.com/deep.atom\"/>
           </outline>
         </outline>
       </outline>
     </body>
   </opml>")

;; =============================================================================
;; parse-opml
;; =============================================================================

(deftest parse-opml-extracts-title
  (testing "parse-opml extracts the head/title"
    (let [parsed (opml/parse-opml (read-sample))]
      (is (= "Sample Feed Subscriptions" (:title parsed))))))

(deftest parse-opml-returns-feed-vector
  (testing "parse-opml returns a vector of feed maps"
    (let [parsed (opml/parse-opml (read-sample))]
      (is (vector? (:feeds parsed)))
      (is (= 5 (count (:feeds parsed)))
          "Sample has 5 feeds across 2 top-level categories + 1 uncategorised"))))

(deftest parse-opml-feed-shape
  (testing "each feed entry has the documented keys"
    (let [feed (-> (opml/parse-opml minimal-opml) :feeds first)]
      (is (= "xkcd" (:title feed)))
      (is (= "https://xkcd.com/atom.xml" (:url feed)))
      (is (= "atom" (:type feed))))))

(deftest parse-opml-attaches-category
  (testing "child feeds inherit the title of their container outline as :category"
    (let [parsed (opml/parse-opml (read-sample))
          by-title (into {} (map (juxt :title identity) (:feeds parsed)))]
      (is (= "Tech" (:category (get by-title "Hacker News"))))
      (is (= "Tech" (:category (get by-title "xkcd"))))
      (is (= "News" (:category (get by-title "BBC News")))))))

(deftest parse-opml-nested-categories
  (testing "deeply nested categories keep the most-specific category"
    (let [parsed (opml/parse-opml (read-sample))
          by-title (into {} (map (juxt :title identity) (:feeds parsed)))]
      (is (= "Clojure" (:category (get by-title "Clojure Releases")))
          "Nested outline title overrides the parent category for its descendants"))))

(deftest parse-opml-uncategorised-feed
  (testing "feeds directly under <body> have no :category"
    (let [parsed (opml/parse-opml (read-sample))
          by-title (into {} (map (juxt :title identity) (:feeds parsed)))]
      (is (nil? (:category (get by-title "Uncategorised Feed")))))))

(deftest parse-opml-empty-body
  (testing "an OPML with an empty body returns no feeds"
    (let [parsed (opml/parse-opml empty-body-opml)]
      (is (= "Empty" (:title parsed)))
      (is (= [] (:feeds parsed))))))

(deftest parse-opml-deep-nesting
  (testing "arbitrary nesting depth still finds feed outlines"
    (let [parsed (opml/parse-opml deeply-nested-opml)]
      (is (= 1 (count (:feeds parsed))))
      (is (= "https://example.com/deep.atom" (-> parsed :feeds first :url))))))

;; =============================================================================
;; extract-feed-urls
;; =============================================================================

(deftest extract-feed-urls-returns-vector
  (testing "extract-feed-urls returns a vector in document order"
    (let [urls (-> (read-sample) opml/parse-opml opml/extract-feed-urls)]
      (is (vector? urls))
      (is (= 5 (count urls)))
      (is (= "https://hnrss.org/frontpage" (first urls))
          "Document order is preserved")
      (is (= "https://example.com/uncategorised.atom" (last urls))))))

;; =============================================================================
;; validate-opml-feeds
;; =============================================================================

(def ^:private valid-atom-feed-xml
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
   <feed xmlns=\"http://www.w3.org/2005/Atom\">
     <title>Sample</title>
     <id>urn:uuid:60a76c80-d399-11d9-b93C-0003939e0af6</id>
     <updated>2026-06-21T12:00:00Z</updated>
     <entry>
       <title>Friday, June 19</title>
       <id>urn:uuid:1225c695-cfb8-4ebb-aaaa-80da344efa6a</id>
       <updated>2026-06-19T12:00:00Z</updated>
       <link href=\"https://example.com/1/\" rel=\"alternate\"/>
     </entry>
   </feed>")

(def ^:private invalid-atom-feed-xml
  ;; Missing <id> and <updated> on the feed.
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
   <feed xmlns=\"http://www.w3.org/2005/Atom\">
     <title>Broken</title>
   </feed>")

(deftest validate-opml-feeds-aggregates-results
  (testing "validate-opml-feeds aggregates per-feed validation outcomes"
    (let [opml-parsed (opml/parse-opml minimal-opml)
          ;; Stub fetcher: always returns a valid feed regardless of URL.
          fetch       (constantly valid-atom-feed-xml)
          result      (opml/validate-opml-feeds opml-parsed {:fetch fetch})]
      (is (= 1 (:total result)))
      (is (= 1 (:valid result)))
      (is (= 0 (:invalid result)))
      (is (vector? (:results result)))
      (is (= "https://xkcd.com/atom.xml" (-> result :results first :url))))))

(deftest validate-opml-feeds-mixed-results
  (testing "validate-opml-feeds reports valid and invalid counts correctly"
    (let [opml-parsed (opml/parse-opml (read-sample))
          ;; Alternate good/bad feeds based on URL contents.
          fetch       (fn [url]
                        (if (re-find #"xkcd|hnrss|clojure" url)
                          valid-atom-feed-xml
                          invalid-atom-feed-xml))
          result      (opml/validate-opml-feeds opml-parsed {:fetch fetch})]
      (is (= 5 (:total result)))
      (is (= (+ (:valid result) (:invalid result)) (:total result))
          "valid + invalid = total")
      (is (pos? (:valid result)))
      (is (pos? (:invalid result)))
      (is (seq (:errors result))
          "Errors are flattened across all invalid feeds"))))

(deftest validate-opml-feeds-fetch-failure-isolated
  (testing "a single fetch failure does not abort the batch"
    (let [opml-parsed (opml/parse-opml (read-sample))
          fetch       (fn [url]
                        (if (re-find #"xkcd" url)
                          (throw (ex-info "boom" {:url url}))
                          valid-atom-feed-xml))
          result      (opml/validate-opml-feeds opml-parsed {:fetch fetch})]
      (is (= 5 (:total result)))
      (is (= 4 (:valid result)))
      (is (= 1 (:invalid result)))
      (is (some #(= :fetch-failed (:code %)) (:errors result))))))

(deftest validate-opml-feeds-requires-fetch
  (testing "validate-opml-feeds throws if no :fetch is provided"
    (let [opml-parsed (opml/parse-opml minimal-opml)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (opml/validate-opml-feeds opml-parsed {}))))))

(deftest validate-opml-feeds-custom-validator
  (testing "a custom :validate function is used in place of the default"
    (let [opml-parsed (opml/parse-opml minimal-opml)
          calls       (atom 0)
          stub-valid  (fn [_] (swap! calls inc)
                              {:valid? true :errors [] :warnings []})
          result      (opml/validate-opml-feeds opml-parsed
                                                {:fetch (constantly "<feed/>")
                                                 :validate stub-valid})]
      (is (= 1 @calls))
      (is (= 1 (:valid result))))))
