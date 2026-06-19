;; live-examples.clj - Validate real-world Atom feeds
;;
;; Usage: Start REPL with `make repl`, connect from Emacs with cider-connect,
;; then evaluate forms with C-c C-e or C-c C-c
;;
;; Feeds tested:
;; - Planet Emacslife (aggregated Emacs blogs)
;; - Sacha Chua's Emacs blog
;; - Clojure releases (GitHub)
;; - Emacs releases (GitHub)
;; - Uncle Bob's Clean Coder blog
;; - Martin Kleppmann's blog
;; - XKCD

(ns live-examples
  (:require [atom-validator.core :as v]
            [clojure.pprint :refer [pprint]]))

;; ============================================================
;; Feed URLs
;; ============================================================

(def feeds
  {:planet-emacs    "https://planet.emacslife.com/atom.xml"
   :sacha-chua      "https://sachachua.com/blog/feed/atom/"
   :clojure         "https://github.com/clojure/clojure/releases.atom"
   :emacs           "https://github.com/emacs-mirror/emacs/releases.atom"
   :clean-coder     "https://blog.cleancoder.com/atom.xml"
   :martin-kleppmann "https://feeds.feedburner.com/martinkl"
   :xkcd            "https://xkcd.com/atom.xml"})

;; ============================================================
;; Helper functions
;; ============================================================

(defn fetch-feed
  "Fetch and parse an Atom feed from URL"
  [url]
  (println "Fetching:" url)
  (let [xml (slurp url)]
    (v/parse-feed xml)))

(defn validate-url
  "Validate a feed from URL, return summary"
  [url]
  (let [result (v/validate-feed (slurp url))]
    {:valid?   (:valid? result)
     :errors   (count (:errors result))
     :warnings (count (:warnings result))
     :title    (get-in result [:feed :title])}))

(defn validate-all
  "Validate all feeds, return summary table"
  []
  (println "\n=== Validating All Feeds ===\n")
  (doseq [[name url] feeds]
    (print (format "%-18s " (str name)))
    (flush)
    (try
      (let [r (validate-url url)]
        (println (format "%s  errors: %d  warnings: %d  \"%s\""
                         (if (:valid? r) "PASS" "FAIL")
                         (:errors r)
                         (:warnings r)
                         (subs (:title r "") 0 (min 40 (count (:title r "")))))))
      (catch Exception e
        (println "ERROR:" (.getMessage e))))))

;; ============================================================
;; Example 1: Planet Emacslife
;; ============================================================

(comment
  ;; Fetch and inspect Planet Emacslife feed
  (def emacs-feed (fetch-feed (:planet-emacs feeds)))

  ;; Check feed metadata
  (:title emacs-feed)
  ;; => "Planet Emacslife"

  (:id emacs-feed)
  ;; => "..."

  ;; How many entries?
  (count (:entries emacs-feed))
  ;; => ~50

  ;; Sample entry titles
  (->> (:entries emacs-feed)
       (take 5)
       (map :title))

  ;; Validate the feed
  (def result (v/validate-feed (slurp (:planet-emacs feeds))))

  (:valid? result)
  ;; => true/false

  ;; Check for day-of-week issues
  (->> (:errors result)
       (filter #(= :day-of-week-mismatch (:code %))))

  ;; Check for stale feed timestamp
  (->> (:errors result)
       (filter #(= :stale-feed-updated (:code %))))
  )

;; ============================================================
;; Example 2: Sacha Chua's Emacs News
;; ============================================================

(comment
  ;; Weekly Emacs news aggregation
  (def sacha-feed (fetch-feed (:sacha-chua feeds)))

  (:title sacha-feed)
  ;; => "sacha chua :: living an awesome life"

  ;; Recent posts
  (->> (:entries sacha-feed)
       (take 5)
       (map :title))

  ;; Validate structure
  (def sacha-result (v/validate-feed (slurp (:sacha-chua feeds))))
  (:valid? sacha-result)
  )

;; ============================================================
;; Example 3: GitHub Release Feeds
;; ============================================================

(comment
  ;; Clojure releases
  (def clj-feed (fetch-feed (:clojure feeds)))

  (:title clj-feed)
  ;; => "Release notes from clojure"

  ;; Latest releases
  (->> (:entries clj-feed)
       (take 3)
       (map (juxt :title :updated)))

  ;; Emacs releases
  (def emacs-rel (fetch-feed (:emacs feeds)))

  (->> (:entries emacs-rel)
       (take 3)
       (map :title))
  ;; => ("Emacs 30.1" "Emacs 29.4" ...)

  ;; Validate both
  (v/valid? (slurp (:clojure feeds)))
  (v/valid? (slurp (:emacs feeds)))
  )

;; ============================================================
;; Example 4: Validate All Feeds
;; ============================================================

(comment
  ;; Run validation across all feeds
  (validate-all)

  ;; Output:
  ;; === Validating All Feeds ===
  ;;
  ;; :planet-emacs      PASS  errors: 0  warnings: 2  "Planet Emacslife"
  ;; :sacha-chua        PASS  errors: 0  warnings: 0  "sacha chua :: living an awesome..."
  ;; :clojure           PASS  errors: 0  warnings: 0  "Release notes from clojure"
  ;; ...
  )

;; ============================================================
;; Example 5: Find Problematic Entries
;; ============================================================

(comment
  ;; Find entries with validation issues
  (defn find-issues [url]
    (let [feed (v/parse-feed (slurp url))]
      (->> (:entries feed)
           (map (fn [e]
                  (let [r (v/validate-entry e)]
                    (when-not (:valid? r)
                      {:title (:title e)
                       :errors (map :code (:errors r))}))))
           (remove nil?))))

  ;; Check Planet Emacslife for issues
  (find-issues (:planet-emacs feeds))

  ;; Check all feeds
  (doseq [[name url] feeds]
    (let [issues (find-issues url)]
      (when (seq issues)
        (println "\n" name "has" (count issues) "issues:")
        (doseq [i (take 3 issues)]
          (println "  -" (:title i) "->" (:errors i))))))
  )

;; ============================================================
;; Example 6: URL Validation
;; ============================================================

(comment
  ;; Check for suspicious URLs in feed entries
  (require '[atom-validator.url :as url])

  (defn check-urls [feed-url]
    (let [feed (v/parse-feed (slurp feed-url))]
      (->> (:entries feed)
           (mapcat :links)
           (map :href)
           (remove nil?)
           (map (fn [u] [u (url/validate-url u :context :link)]))
           (filter (fn [[_ r]] (not (:valid? r)))))))

  ;; Find invalid URLs in Emacs feed
  (check-urls (:planet-emacs feeds))
  )

;; ============================================================
;; REPL Startup Banner
;; ============================================================

(println "
╔════════════════════════════════════════════════════════════╗
║  atom-validator Live Examples                              ║
║                                                            ║
║  Available feeds:                                          ║
║    (:planet-emacs feeds)   - Planet Emacslife              ║
║    (:sacha-chua feeds)     - Sacha Chua's blog             ║
║    (:clojure feeds)        - Clojure releases              ║
║    (:emacs feeds)          - Emacs releases                ║
║    (:clean-coder feeds)    - Uncle Bob's blog              ║
║    (:xkcd feeds)           - XKCD                          ║
║                                                            ║
║  Quick commands:                                           ║
║    (validate-all)          - Test all feeds                ║
║    (fetch-feed url)        - Parse a feed                  ║
║    (validate-url url)      - Validate a feed               ║
║                                                            ║
║  Evaluate code blocks in (comment ...) with C-c C-c        ║
╚════════════════════════════════════════════════════════════╝
")
