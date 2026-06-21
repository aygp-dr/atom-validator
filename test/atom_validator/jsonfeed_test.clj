(ns atom-validator.jsonfeed-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [atom-validator.jsonfeed :as jf]
            [atom-validator.core :as v]))

;; =============================================================================
;; Version validation
;; =============================================================================

(deftest missing-version
  (testing "Feed without version is invalid"
    (let [feed {:title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Feed without version should be invalid")
      (is (some #(= :missing-version (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest invalid-version
  (testing "Feed with invalid version is invalid"
    (let [feed {:version "1.1"
                :title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Feed with invalid version should be invalid")
      (is (some #(= :invalid-version (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest valid-version-1
  (testing "Feed with version 1 URL is valid"
    (let [feed {:version "https://jsonfeed.org/version/1"
                :title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(#{:missing-version :invalid-version} (:code %)) (:errors result)))
          "Should not flag valid version 1"))))

(deftest valid-version-1-1
  (testing "Feed with version 1.1 URL is valid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(#{:missing-version :invalid-version} (:code %)) (:errors result)))
          "Should not flag valid version 1.1"))))

;; =============================================================================
;; Title validation
;; =============================================================================

(deftest missing-title
  (testing "Feed without title is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Feed without title should be invalid")
      (is (some #(= :missing-title (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest empty-title
  (testing "Feed with blank title is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "   "
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(= :missing-title (:code %)) (:errors result))
          "Blank title should be flagged"))))

;; =============================================================================
;; Items validation
;; =============================================================================

(deftest missing-items
  (testing "Feed without items is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Feed without items should be invalid")
      (is (some #(= :missing-items (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest invalid-items-type
  (testing "Feed with non-array items is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items "not an array"}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Feed with non-array items should be invalid")
      (is (some #(= :invalid-items (:code %)) (:errors result))
          "Error should have correct code"))))

;; =============================================================================
;; Item ID validation
;; =============================================================================

(deftest missing-item-id
  (testing "Item without id is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Item without id should be invalid")
      (is (some #(= :missing-item-id (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest blank-item-id
  (testing "Item with blank id is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(= :missing-item-id (:code %)) (:errors result))
          "Blank id should be flagged"))))

;; =============================================================================
;; Item content validation
;; =============================================================================

(deftest missing-item-content
  (testing "Item without content is invalid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"}]}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Item without content should be invalid")
      (is (some #(= :missing-item-content (:code %)) (:errors result))
          "Error should have correct code"))))

(deftest content-html-only
  (testing "Item with only content_html is valid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1" :content_html "<p>Hello</p>"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(= :missing-item-content (:code %)) (:errors result)))
          "Should not flag item with content_html"))))

(deftest content-text-only
  (testing "Item with only content_text is valid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(= :missing-item-content (:code %)) (:errors result)))
          "Should not flag item with content_text"))))

(deftest both-content-types
  (testing "Item with both content types is valid"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"
                         :content_html "<p>Hello</p>"
                         :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(= :missing-item-content (:code %)) (:errors result)))
          "Should not flag item with both content types"))))

;; =============================================================================
;; URL validation
;; =============================================================================

(deftest invalid-home-page-url
  (testing "Invalid home_page_url is flagged"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :home_page_url "not-a-url"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(#{:invalid-url :invalid-url-scheme :invalid-url-host} (:code %))
                (:errors result))
          "Invalid home_page_url should be flagged"))))

(deftest invalid-feed-url
  (testing "Invalid feed_url is flagged"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :feed_url "example.com/feed.json"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(#{:invalid-url :invalid-url-scheme :invalid-url-host} (:code %))
                (:errors result))
          "Invalid feed_url should be flagged"))))

(deftest valid-urls
  (testing "Valid URLs pass validation"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :home_page_url "https://example.com/"
                :feed_url "https://example.com/feed.json"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(#{:invalid-url :invalid-url-scheme :invalid-url-host} (:code %))
                     (:errors result)))
          "Valid URLs should not be flagged"))))

(deftest invalid-item-url
  (testing "Invalid item URL is flagged"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"
                         :content_text "Hello"
                         :url "wal.shsite/article/1"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(#{:invalid-url :invalid-url-scheme :invalid-url-host} (:code %))
                (:errors result))
          "Invalid item URL should be flagged"))))

;; =============================================================================
;; Date validation
;; =============================================================================

(deftest invalid-date-published
  (testing "Invalid date_published is flagged"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"
                         :content_text "Hello"
                         :date_published "not-a-date"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(= :invalid-date (:code %)) (:errors result))
          "Invalid date_published should be flagged"))))

(deftest invalid-date-modified
  (testing "Invalid date_modified is flagged"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"
                         :content_text "Hello"
                         :date_modified "June 19, 2026"}]}
          result (jf/validate-json-feed feed)]
      (is (some #(= :invalid-date (:code %)) (:errors result))
          "Invalid date_modified should be flagged"))))

(deftest valid-iso8601-dates
  (testing "Valid ISO 8601 dates pass validation"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1"
                         :content_text "Hello"
                         :date_published "2026-06-19T12:00:00Z"
                         :date_modified "2026-06-19T15:30:00+05:00"}]}
          result (jf/validate-json-feed feed)]
      (is (not (some #(= :invalid-date (:code %)) (:errors result)))
          "Valid ISO 8601 dates should not be flagged"))))

;; =============================================================================
;; Integration tests
;; =============================================================================

(deftest minimal-valid-feed
  (testing "Minimal valid JSON Feed"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "Test Feed"
                :items [{:id "1" :content_text "Hello"}]}
          result (jf/validate-json-feed feed)]
      (is (:valid? result)
          "Minimal valid feed should pass"))))

(deftest full-valid-feed
  (testing "Full valid JSON Feed"
    (let [feed {:version "https://jsonfeed.org/version/1.1"
                :title "My Example Feed"
                :home_page_url "https://example.org/"
                :feed_url "https://example.org/feed.json"
                :description "A sample feed for testing"
                :authors [{:name "John Doe" :url "https://example.org/johndoe"}]
                :language "en-US"
                :items [{:id "2"
                         :content_html "<p>This is the second item.</p>"
                         :url "https://example.org/second-item"
                         :date_published "2026-06-19T10:00:00Z"}
                        {:id "1"
                         :content_html "<p>This is the first item.</p>"
                         :url "https://example.org/first-item"
                         :date_published "2026-06-18T10:00:00Z"}]}
          result (jf/validate-json-feed feed)]
      (is (:valid? result)
          "Full valid feed should pass"))))

(deftest empty-feed-invalid
  (testing "Empty feed should be invalid"
    (let [feed {}
          result (jf/validate-json-feed feed)]
      (is (not (:valid? result))
          "Empty feed should be invalid"))))

;; =============================================================================
;; JSON string parsing
;; =============================================================================

(deftest json-string-parsing
  (testing "JSON Feed from string"
    (let [json-str "{\"version\":\"https://jsonfeed.org/version/1.1\",\"title\":\"Test\",\"items\":[{\"id\":\"1\",\"content_text\":\"Hello\"}]}"
          result (jf/validate-json-feed json-str)]
      (is (:valid? result)
          "JSON string should be parsed and validated"))))

;; =============================================================================
;; Core integration (auto-detect)
;; =============================================================================

(deftest core-auto-detect-json-feed
  (testing "Core validate-feed auto-detects JSON Feed"
    (let [json-str "{\"version\":\"https://jsonfeed.org/version/1.1\",\"title\":\"Test\",\"items\":[{\"id\":\"1\",\"content_text\":\"Hello\"}]}"
          result (v/validate-feed json-str)]
      (is (:valid? result)
          "Core should auto-detect and validate JSON Feed")
      (is (= :json-feed (get-in result [:feed :format]))
          "Format should be :json-feed"))))

(deftest core-explicit-json-feed-format
  (testing "Core validate-feed with explicit :json-feed format"
    (let [json-str "{\"version\":\"https://jsonfeed.org/version/1.1\",\"title\":\"Test\",\"items\":[{\"id\":\"1\",\"content_text\":\"Hello\"}]}"
          result (v/validate-feed json-str {:format :json-feed})]
      (is (:valid? result)
          "Explicit format should work")
      (is (= :json-feed (get-in result [:feed :format]))
          "Format should be :json-feed"))))

;; =============================================================================
;; Property-based tests
;; =============================================================================

(def gen-valid-json-feed
  "Generator for valid JSON Feeds."
  (gen/fmap
   (fn [[title items]]
     {:version "https://jsonfeed.org/version/1.1"
      :title title
      :items (vec items)})
   (gen/tuple
    (gen/not-empty gen/string-alphanumeric)
    (gen/vector
     (gen/fmap
      (fn [[id content]]
        {:id id :content_text content})
      (gen/tuple
       (gen/fmap str gen/nat)
       (gen/not-empty gen/string-alphanumeric)))
     1 5))))

(defspec prop-valid-feeds-are-valid 10
  (prop/for-all [feed gen-valid-json-feed]
    (:valid? (jf/validate-json-feed feed))))

(def gen-feed-missing-version
  "Generator for feeds without version."
  (gen/fmap
   (fn [[title items]]
     {:title title
      :items [{:id "1" :content_text items}]})
   (gen/tuple
    (gen/not-empty gen/string-alphanumeric)
    (gen/not-empty gen/string-alphanumeric))))

(defspec prop-missing-version-detected 10
  (prop/for-all [feed gen-feed-missing-version]
    (some #(= :missing-version (:code %))
          (:errors (jf/validate-json-feed feed)))))

(def gen-feed-missing-item-content
  "Generator for feeds with items missing content."
  (gen/fmap
   (fn [[title id]]
     {:version "https://jsonfeed.org/version/1.1"
      :title title
      :items [{:id id}]})
   (gen/tuple
    (gen/not-empty gen/string-alphanumeric)
    (gen/fmap str gen/nat))))

(defspec prop-missing-content-detected 10
  (prop/for-all [feed gen-feed-missing-item-content]
    (some #(= :missing-item-content (:code %))
          (:errors (jf/validate-json-feed feed)))))
