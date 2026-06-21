(ns atom-validator.rss-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [atom-validator.core :as v]
            [atom-validator.rss :as rss]))

;; =============================================================================
;; RSS 2.0 Channel Validation
;; =============================================================================

(deftest missing-channel-title
  (testing "Channel must have title"
    (let [feed {:format :rss
                :link "https://example.com/"
                :description "A test feed"
                :items []}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid without title")
      (is (some #(= :missing-channel-title (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest missing-channel-link
  (testing "Channel must have link"
    (let [feed {:format :rss
                :title "Test Feed"
                :description "A test feed"
                :items []}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid without link")
      (is (some #(= :missing-channel-link (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest missing-channel-description
  (testing "Channel must have description"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :items []}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid without description")
      (is (some #(= :missing-channel-description (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest valid-minimal-rss-feed
  (testing "Minimal valid RSS feed"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:title "Test Item"
                         :link "https://example.com/1/"}]}]
      (is (:valid? (v/validate-feed feed))
          "Minimal valid feed should pass"))))

;; =============================================================================
;; RSS 2.0 Date Validation (RFC 822)
;; =============================================================================

(deftest invalid-pubdate
  (testing "pubDate must be valid RFC 822 format"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :pub-date "not a real date"
                :items []}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid with bad pubDate")
      (is (some #(= :invalid-pubdate (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest valid-pubdate
  (testing "Valid RFC 822 pubDate should pass"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :pub-date "Sat, 21 Jun 2026 12:00:00 +0000"
                :items [{:title "Test Item"}]}]
      (is (not (some #(= :invalid-pubdate (:code %))
                     (:errors (v/validate-feed feed))))
          "Valid pubDate should not produce error"))))

(deftest invalid-item-pubdate
  (testing "Item pubDate must be valid RFC 822 format"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:title "Test Item"
                         :pub-date "invalid date"}]}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid with bad item pubDate")
      (is (some #(= :invalid-pubdate (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

;; =============================================================================
;; RSS 2.0 Item Validation
;; =============================================================================

(deftest missing-item-content
  (testing "Item must have title OR description"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:link "https://example.com/1/"}]}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid without title or description")
      (is (some #(= :missing-item-content (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest item-with-title-only
  (testing "Item with only title should be valid"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:title "Test Item"}]}]
      (is (:valid? (v/validate-feed feed))
          "Item with title only should be valid"))))

(deftest item-with-description-only
  (testing "Item with only description should be valid"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:description "Some content here"}]}]
      (is (:valid? (v/validate-feed feed))
          "Item with description only should be valid"))))

;; =============================================================================
;; RSS 2.0 GUID Validation
;; =============================================================================

(deftest invalid-guid-empty
  (testing "Empty guid should produce error"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:title "Test Item"
                         :guid {:value "" :is-perma-link "true"}}]}]
      (is (not (:valid? (v/validate-feed feed)))
          "Should be invalid with empty guid")
      (is (some #(= :invalid-guid (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest duplicate-guid-warning
  (testing "Duplicate guids should produce warning"
    (let [feed {:format :rss
                :title "Test Feed"
                :link "https://example.com/"
                :description "A test feed"
                :items [{:title "Item 1"
                         :guid {:value "https://example.com/1/" :is-perma-link "true"}}
                        {:title "Item 2"
                         :guid {:value "https://example.com/1/" :is-perma-link "true"}}]}]
      (is (some #(= :duplicate-guid (:code %))
                (:warnings (v/validate-feed feed)))
          "Should warn about duplicate guids"))))

;; =============================================================================
;; Format Detection
;; =============================================================================

(deftest detect-rss-format
  (testing "Should detect RSS format from XML"
    (let [rss-xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Test Feed</title>
    <link>https://example.com/</link>
    <description>A test feed</description>
    <item>
      <title>Test Item</title>
    </item>
  </channel>
</rss>"]
      (is (= :rss (v/detect-feed-format rss-xml))
          "Should detect RSS format"))))

(deftest detect-atom-format
  (testing "Should detect Atom format from XML"
    (let [atom-xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
  <title>Test Feed</title>
  <id>urn:uuid:123</id>
  <updated>2026-06-21T12:00:00Z</updated>
</feed>"]
      (is (= :atom (v/detect-feed-format atom-xml))
          "Should detect Atom format"))))

;; =============================================================================
;; XML Parsing Integration
;; =============================================================================

(deftest parse-rss-from-xml
  (testing "Should parse RSS XML correctly"
    (let [rss-xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Test Feed</title>
    <link>https://example.com/</link>
    <description>A test feed</description>
    <language>en-us</language>
    <pubDate>Sat, 21 Jun 2026 12:00:00 +0000</pubDate>
    <generator>Test Generator</generator>
    <item>
      <title>Test Item</title>
      <link>https://example.com/1/</link>
      <description>Item description</description>
      <pubDate>Sat, 21 Jun 2026 10:00:00 +0000</pubDate>
      <guid isPermaLink=\"true\">https://example.com/1/</guid>
    </item>
  </channel>
</rss>"
          parsed (v/parse-feed rss-xml)]
      (is (= :rss (:format parsed))
          "Should have RSS format marker")
      (is (= "Test Feed" (:title parsed))
          "Should parse title")
      (is (= "https://example.com/" (:link parsed))
          "Should parse link")
      (is (= "A test feed" (:description parsed))
          "Should parse description")
      (is (= "en-us" (:language parsed))
          "Should parse language")
      (is (= "Test Generator" (:generator parsed))
          "Should parse generator")
      (is (= 1 (count (:items parsed)))
          "Should parse items")
      (is (= "Test Item" (-> parsed :items first :title))
          "Should parse item title"))))

(deftest validate-rss-from-xml
  (testing "Should validate RSS XML end-to-end"
    (let [valid-rss "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Test Feed</title>
    <link>https://example.com/</link>
    <description>A test feed</description>
    <item>
      <title>Test Item</title>
    </item>
  </channel>
</rss>"
          result (v/validate-feed valid-rss)]
      (is (:valid? result)
          "Valid RSS should pass validation")
      (is (= :rss (-> result :feed :format))
          "Should include format in result"))))

(deftest validate-invalid-rss-from-xml
  (testing "Should detect missing required elements in RSS XML"
    (let [invalid-rss "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Test Feed</title>
    <!-- missing link and description -->
  </channel>
</rss>"
          result (v/validate-feed invalid-rss)]
      (is (not (:valid? result))
          "Invalid RSS should fail validation")
      (is (some #(= :missing-channel-link (:code %)) (:errors result))
          "Should detect missing link")
      (is (some #(= :missing-channel-description (:code %)) (:errors result))
          "Should detect missing description"))))

;; =============================================================================
;; RFC 822 Date Parsing Tests
;; =============================================================================

(deftest parse-rfc822-dates
  (testing "Should parse various RFC 822 date formats"
    (is (some? (rss/parse-rfc822 "Sat, 21 Jun 2026 12:00:00 +0000"))
        "Standard RFC 822 format")
    (is (some? (rss/parse-rfc822 "Sat, 21 Jun 2026 12:00:00 GMT"))
        "RFC 822 with timezone name")
    (is (some? (rss/parse-rfc822 "21 Jun 2026 12:00:00 +0000"))
        "RFC 822 without day name")
    (is (nil? (rss/parse-rfc822 "2026-06-21T12:00:00Z"))
        "ISO 8601 should not parse as RFC 822")
    (is (nil? (rss/parse-rfc822 "not a date"))
        "Invalid string should return nil")
    (is (nil? (rss/parse-rfc822 nil))
        "nil should return nil")
    (is (nil? (rss/parse-rfc822 ""))
        "Empty string should return nil")))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(def gen-rss-title
  "Generate a title string."
  (gen/fmap
   (fn [words] (clojure.string/join " " words))
   (gen/vector (gen/elements ["News" "Update" "Article" "Post" "Story"]) 1 3)))

(def gen-valid-rss-feed
  "Generator for valid RSS feeds."
  (gen/fmap
   (fn [[title link desc item-title]]
     {:format :rss
      :title title
      :link (str "https://example.com/" link "/")
      :description desc
      :items [{:title item-title}]})
   (gen/tuple gen-rss-title gen-rss-title gen-rss-title gen-rss-title)))

(defspec prop-valid-rss-feeds-are-valid 20
  (prop/for-all [feed gen-valid-rss-feed]
    (:valid? (v/validate-feed feed))))

(def gen-invalid-rss-feed
  "Generator for RSS feeds missing required elements."
  (gen/fmap
   (fn [[title link desc]]
     ;; Randomly omit one required field
     (let [base {:format :rss
                 :title title
                 :link link
                 :description desc
                 :items [{:title "Test"}]}
           field-to-remove (rand-nth [:title :link :description])]
       (assoc base field-to-remove nil)))
   (gen/tuple gen-rss-title gen-rss-title gen-rss-title)))

(defspec prop-incomplete-rss-feeds-are-invalid 20
  (prop/for-all [feed gen-invalid-rss-feed]
    (not (:valid? (v/validate-feed feed)))))
