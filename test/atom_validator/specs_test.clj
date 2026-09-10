(ns atom-validator.specs-test
  "Generative checks for every pure s/fdef'd fn, plus data-spec sanity.
  Per https://clojure.org/guides/spec (Testing)."
  (:require [atom-validator.core :as v]
            [atom-validator.http]
            [atom-validator.jsonfeed]
            [atom-validator.opml :as opml]
            [atom-validator.parser]
            [atom-validator.rss]
            [atom-validator.rules]
            [atom-validator.semantic]
            [atom-validator.specs :as specs]
            [atom-validator.url]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing]]))

(def ^:private check-opts {:clojure.spec.test.check/opts {:num-tests 50}})

(def ^:private api-nses
  '[atom-validator.core atom-validator.parser atom-validator.rules
    atom-validator.semantic atom-validator.url atom-validator.rss
    atom-validator.jsonfeed atom-validator.opml atom-validator.http])

;; Side-effecting fns: fdef'd for instrumentation, never generatively checked.
(def ^:private side-effecting
  #{`atom-validator.http/fetch-feed           ; HTTP IO
    `atom-validator.http/fetch-and-validate   ; HTTP IO
    `atom-validator.opml/validate-opml-feeds}) ; calls the caller's :fetch fn

(defn- checkable []
  (remove side-effecting (stest/enumerate-namespace api-nses)))

(deftest fdefs-hold-under-generative-testing
  (let [results (stest/check (checkable) check-opts)]
    (is (seq results) "expected at least one fdef'd fn to check")
    (doseq [r results]
      (testing (str (:sym r))
        (is (nil? (:failure r))
            (pr-str (stest/abbrev-result r)))))))

(deftest data-specs-generate-and-conform
  (doseq [k [::specs/atom-feed ::specs/entry ::specs/rss-feed ::specs/json-feed
             ::specs/feed-list ::specs/feed-document ::specs/issue ::specs/result]]
    (testing (str k)
      (is (every? (fn [[v _]] (s/valid? k v)) (s/exercise k 10))))))

(defn- fixture [file-name]
  (slurp (io/file "test/fixtures" file-name)))

(deftest real-values-conform
  (testing "parsed fixtures"
    (is (s/valid? ::specs/parsed-atom-feed (v/parse-feed (fixture "valid-feed.xml"))))
    (is (s/valid? ::specs/parsed-atom-feed (v/parse-feed (fixture "github-clojure.xml"))))
    (is (s/valid? ::specs/parsed-rss-feed (v/parse-feed (fixture "valid-rss.xml"))))
    (is (s/valid? ::specs/json-feed (v/parse-json-feed (fixture "feed.json"))))
    (is (s/valid? ::specs/parsed-feed-list (opml/parse-opml (fixture "sample.opml")))))
  (testing "the day-of-week example from the docs"
    (let [entry {:title "Morning Brief: Thursday, June 19"
                 :updated "2026-06-19T00:00:00Z"
                 :id "urn:uuid:123"}]
      (is (s/valid? ::specs/entry entry))
      (is (s/valid? ::specs/entry-result (v/validate-entry entry)))))
  (testing "validation results for the fixture feeds"
    (doseq [f ["valid-feed.xml" "invalid-feed.xml" "valid-rss.xml" "feed.json" "xkcd.xml"]]
      (is (s/valid? ::specs/result (v/validate-feed (fixture f))) f))))

(deftest renderers-round-trip
  (testing "feed lists survive feed-list->opml then parse-opml"
    (doseq [fl (gen/sample (s/gen ::specs/feed-list) 20)]
      (let [parsed (opml/parse-opml (specs/feed-list->opml fl))]
        (is (= (:title fl) (:title parsed)))
        (is (= (mapv #(merge {:title nil :type nil :html-url nil} %) (:feeds fl))
               (:feeds parsed))))))
  (testing "Atom feeds keep their title and entry ids through atom-feed->xml"
    (doseq [f (gen/sample (s/gen ::specs/atom-feed) 20)]
      (let [parsed (v/parse-feed (specs/atom-feed->xml f))]
        (is (= (:title f) (:title parsed)))
        (is (= (map :id (:entries f)) (map :id (:entries parsed))))))))
