(ns atom-validator.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [atom-validator.core :as v]
            [atom-validator.generators :as g]))

;; =============================================================================
;; Issue #1: Day-of-week mismatch detection
;; =============================================================================

(deftest day-of-week-mismatch
  (testing "Title day-of-week must match <updated> date"
    (let [entry {:title "Morning Brief: Thursday, June 19"
                 :updated "2026-06-19T00:00:00Z"
                 :id "urn:uuid:test-1"
                 :links [{:href "https://example.com/1/" :rel "alternate"}]}]
      ;; June 19, 2026 is a Friday, not Thursday
      (is (not (:valid? (v/validate-entry entry)))
          "Should detect day-of-week mismatch")
      (is (some #(= :day-of-week-mismatch (:code %))
                (:errors (v/validate-entry entry)))
          "Error should have correct code"))))

(deftest day-of-week-correct
  (testing "Matching day-of-week should pass"
    (let [entry {:title "Morning Brief: Friday, June 19"
                 :updated "2026-06-19T00:00:00Z"
                 :id "urn:uuid:test-2"
                 :links [{:href "https://example.com/2/" :rel "alternate"}]}]
      ;; June 19, 2026 is a Friday
      (is (not (some #(= :day-of-week-mismatch (:code %))
                     (:errors (v/validate-entry entry))))
          "Should not flag matching day"))))

(defspec prop-mismatched-days-always-invalid 20
  (prop/for-all [entry (g/gen-entry-with-mismatched-day)]
    (let [result (v/validate-entry entry)]
      (some #(= :day-of-week-mismatch (:code %)) (:errors result)))))

(defspec prop-correct-days-no-day-error 20
  (prop/for-all [entry (g/gen-entry-with-correct-day)]
    (let [result (v/validate-entry entry)]
      (not (some #(= :day-of-week-mismatch (:code %)) (:errors result))))))

;; =============================================================================
;; Issue #2: Feed freshness (updated timestamp)
;; =============================================================================

(deftest feed-updated-not-stale
  (testing "Feed <updated> must be >= max(entry <updated>)"
    (let [feed {:id "urn:uuid:feed-1"
                :title "Test Feed"
                :updated "2026-06-14T00:00:00Z"
                :entries [{:id "urn:uuid:entry-1"
                           :title "Entry 1"
                           :updated "2026-06-19T00:00:00Z"
                           :links [{:href "https://example.com/1/" :rel "alternate"}]}
                          {:id "urn:uuid:entry-2"
                           :title "Entry 2"
                           :updated "2026-06-18T00:00:00Z"
                           :links [{:href "https://example.com/2/" :rel "alternate"}]}]}]
      (is (not (:valid? (v/validate-feed feed)))
          "Feed updated is older than newest entry")
      (is (some #(= :stale-feed-updated (:code %))
                (:errors (v/validate-feed feed)))
          "Error should have correct code"))))

(deftest feed-updated-fresh
  (testing "Feed <updated> >= max(entry <updated>) should pass"
    (let [feed {:id "urn:uuid:feed-2"
                :title "Test Feed"
                :updated "2026-06-20T00:00:00Z"
                :entries [{:id "urn:uuid:entry-1"
                           :title "Entry 1"
                           :updated "2026-06-19T00:00:00Z"
                           :links [{:href "https://example.com/1/" :rel "alternate"}]}]}]
      (is (not (some #(= :stale-feed-updated (:code %))
                     (:errors (v/validate-feed feed))))
          "Should not flag fresh feed"))))

(defspec prop-stale-feeds-always-invalid 10
  (prop/for-all [feed (g/gen-feed-with-stale-updated)]
    (let [result (v/validate-feed feed)]
      (some #(= :stale-feed-updated (:code %)) (:errors result)))))

;; =============================================================================
;; Issue #3: URL validation
;; =============================================================================

(deftest entry-urls-valid
  (testing "Entry URLs must be valid absolute URLs"
    (let [entry {:id "https://wal.shsite/events/gophercon-2026/"
                 :title "GopherCon 2026"
                 :updated "2026-06-19T00:00:00Z"
                 :links [{:href "https://wal.shsite/events/gophercon-2026/"
                          :rel "alternate"}]}]
      (is (not (:valid? (v/validate-entry entry)))
          "URL host contains invalid pattern (wal.shsite)")
      (is (some #(= :invalid-url-host (:code %))
                (:errors (v/validate-entry entry)))
          "Error should have correct code"))))

(deftest entry-urls-with-valid-host
  (testing "Valid URLs should pass"
    (let [entry {:id "https://example.com/events/gophercon-2026/"
                 :title "GopherCon 2026"
                 :updated "2026-06-19T00:00:00Z"
                 :links [{:href "https://example.com/events/gophercon-2026/"
                          :rel "alternate"}]}]
      (is (not (some #(#{:invalid-url-host :invalid-url :invalid-url-scheme} (:code %))
                     (:errors (v/validate-entry entry))))
          "Should not flag valid URLs"))))

(defspec prop-invalid-url-hosts-detected 20
  (prop/for-all [entry (g/gen-entry-with-invalid-url)]
    (let [result (v/validate-entry entry)]
      (some #(#{:invalid-url-host :invalid-url} (:code %)) (:errors result)))))

(defspec prop-valid-entries-have-no-url-errors 20
  (prop/for-all [entry g/gen-valid-atom-entry]
    (let [result (v/validate-entry entry)]
      (not (some #(#{:invalid-url-host :invalid-url :invalid-url-scheme} (:code %))
                 (:errors result))))))

;; =============================================================================
;; Integration tests
;; =============================================================================

(defspec prop-valid-feeds-are-valid 10
  (prop/for-all [feed g/gen-valid-atom-feed]
    (:valid? (v/validate-feed feed {:semantic? false}))))

(deftest empty-feed-invalid
  (testing "Empty feed should be invalid"
    (let [feed {}]
      (is (not (:valid? (v/validate-feed feed)))
          "Empty feed should be invalid"))))

(deftest invalid-xml-does-not-throw
  (testing "Malformed/non-feed content returns :invalid-xml instead of throwing"
    (let [result (v/validate-feed "not xml at all <<<>>>")]
      (is (not (:valid? result)))
      (is (some #(= :invalid-xml (:code %)) (:errors result))
          "Garbage input should yield an :invalid-xml error")))
  (testing "An HTML error page (e.g. a bot wall) does not throw"
    (let [result (v/validate-feed "<html><head><title>Just a moment...</title>&</head>")]
      (is (not (:valid? result))
          "Should degrade to a validation result, not an exception"))))

(deftest minimal-valid-feed
  (testing "Minimal valid feed"
    (let [feed {:id "urn:uuid:feed-1"
                :title "Test Feed"
                :updated "2026-06-19T00:00:00Z"
                :authors [{:name "Test Author"}]
                :entries [{:id "urn:uuid:entry-1"
                           :title "Entry 1"
                           :updated "2026-06-19T00:00:00Z"
                           :links [{:href "https://example.com/1/" :rel "alternate"}]}]}]
      (is (:valid? (v/validate-feed feed))
          "Minimal valid feed should pass"))))
