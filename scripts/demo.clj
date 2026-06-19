;; atom-validator Demo Script
;; Run with: clj -A:dev:test -M scripts/demo.clj

(require '[atom-validator.core :as v])

(println "")
(println "╔════════════════════════════════════════════╗")
(println "║       atom-validator Demo                  ║")
(println "║       RFC 4287 Atom Feed Validation        ║")
(println "╚════════════════════════════════════════════╝")
(println "")

;; Demo 1: Day-of-week mismatch (Issue #1)
(println "━━━ Issue #1: Day-of-Week Mismatch ━━━")
(println "")
(println "Entry title says 'Thursday, June 19' but 2026-06-19 is Friday!")
(println "")
(def entry1 {:title "Morning Brief: Thursday, June 19"
             :updated "2026-06-19T00:00:00Z"
             :id "urn:uuid:brief-1"
             :links [{:href "https://news.example.com/1/" :rel "alternate"}]})
(println "(v/validate-entry entry1)")
(let [result (v/validate-entry entry1)]
  (println "=> {:valid?" (:valid? result))
  (println "    :errors [" (-> result :errors first :code) "]")
  (println "    :message" (str "\"" (-> result :errors first :message) "\"") "}"))
(println "")

;; Demo 2: Stale feed (Issue #2)
(println "━━━ Issue #2: Stale Feed Timestamp ━━━")
(println "")
(println "Feed updated=2026-06-14 but entry updated=2026-06-19 (newer!)")
(println "")
(def feed2 {:id "urn:uuid:feed-1"
            :title "Stale News"
            :updated "2026-06-14T00:00:00Z"
            :entries [{:id "urn:uuid:entry-1"
                       :title "Breaking News"
                       :updated "2026-06-19T15:00:00Z"
                       :links [{:href "https://news.example.com/breaking/" :rel "alternate"}]}]})
(println "(v/validate-feed feed2)")
(let [result (v/validate-feed feed2)]
  (println "=> {:valid?" (:valid? result))
  (println "    :errors [" (-> result :errors first :code) "]")
  (println "    :message" (str "\"" (-> result :errors first :message) "\"") "}"))
(println "")

;; Demo 3: Invalid URL (Issue #3)
(println "━━━ Issue #3: Invalid URL Host ━━━")
(println "")
(println "URL 'wal.shsite' looks like typo for 'wal.sh/site'")
(println "")
(def entry3 {:id "https://wal.shsite/gophercon/"
             :title "GopherCon 2026"
             :updated "2026-06-19T00:00:00Z"
             :links [{:href "https://wal.shsite/gophercon/" :rel "alternate"}]})
(println "(v/validate-entry entry3)")
(let [result (v/validate-entry entry3)]
  (println "=> {:valid?" (:valid? result))
  (println "    :errors [" (-> result :errors first :code) "]")
  (println "    :message" (str "\"" (-> result :errors first :message) "\"") "}"))
(println "")

;; Demo 4: Valid feed
(println "━━━ Valid Feed ━━━")
(println "")
(def valid-feed {:id "urn:uuid:blog"
                 :title "My Tech Blog"
                 :updated "2026-06-19T12:00:00Z"
                 :entries [{:id "urn:uuid:post-1"
                            :title "Getting Started with Clojure"
                            :updated "2026-06-19T10:00:00Z"
                            :links [{:href "https://example.com/clojure/" :rel "alternate"}]}]})
(println "(v/valid? valid-feed)")
(println "=>" (v/valid? valid-feed))
(println "")

(println "╔════════════════════════════════════════════╗")
(println "║  All validations complete!                 ║")
(println "║  See README.org for integration guide      ║")
(println "╚════════════════════════════════════════════╝")
(println "")

(System/exit 0)
