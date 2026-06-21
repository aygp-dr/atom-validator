#!/usr/bin/env bb
;; Babashka script for validating Atom feeds and OPML feed lists
;;
;; Usage:
;;   bb validate-feed.bb <atom-feed-url>
;;   bb validate-feed.bb --opml <path-to.opml>

(require '[babashka.http-client :as http]
         '[clojure.data.xml :as xml]
         '[clojure.string :as str])

(defn fetch-feed [url]
  (-> (http/get url) :body))

(defn parse-atom [xml-str]
  (let [parsed (xml/parse-str xml-str)]
    {:title (some-> parsed :content first :content first)
     :entries (->> (:content parsed)
                   (filter #(= :entry (:tag %)))
                   count)}))

(defn validate [url]
  (println "Fetching:" url)
  (let [feed (parse-atom (fetch-feed url))]
    (println "Title:" (:title feed))
    (println "Entries:" (:entries feed))
    (println "Status: OK")))

;; ---------------------------------------------------------------------------
;; OPML batch validation
;;
;; Minimal Babashka-only reimplementation of atom-validator.opml so that the
;; example script stays runnable without the JVM library on the classpath.
;; For the full library API, call atom-validator.opml/validate-opml-feeds
;; with a :fetch function (e.g., #(:body (http/get %))).
;; ---------------------------------------------------------------------------

(defn- local-name [kw]
  (let [n (name kw)]
    (if (str/includes? n "/") (second (str/split n #"/")) n)))

(defn- outline-children [el]
  (filter #(and (map? %) (= "outline" (local-name (:tag %)))) (:content el)))

(defn- feed-outline? [o] (some? (get-in o [:attrs :xmlUrl])))

(defn- walk-outlines [outlines category]
  (reduce
    (fn [acc o]
      (if (feed-outline? o)
        (let [a (:attrs o)]
          (conj acc {:title (or (:title a) (:text a))
                     :url (:xmlUrl a)
                     :type (:type a)
                     :category category}))
        (into acc (walk-outlines (outline-children o)
                                 (or (get-in o [:attrs :title])
                                     (get-in o [:attrs :text])
                                     category)))))
    [] outlines))

(defn- parse-opml-file [path]
  (let [root (xml/parse-str (slurp path))
        body (->> (:content root)
                  (filter #(and (map? %) (= "body" (local-name (:tag %)))))
                  first)]
    (walk-outlines (outline-children body) nil)))

(defn validate-opml [path]
  (println "Loading OPML:" path)
  (let [feeds (parse-opml-file path)]
    (println "Found" (count feeds) "feeds")
    (doseq [{:keys [title url category]} feeds]
      (println (format "  [%s] %s -> %s"
                       (or category "uncategorised")
                       (or title "?")
                       url)))
    (println "\nValidating each feed (HEAD only):")
    (let [results (for [{:keys [url title]} feeds]
                    (try
                      (let [body (fetch-feed url)
                            ok?  (or (str/includes? body "<feed")
                                     (str/includes? body "<rss")
                                     (str/starts-with? (str/triml body) "{"))]
                        (println (format "  %s %s"
                                         (if ok? "OK  " "FAIL")
                                         (or title url)))
                        {:url url :valid? ok?})
                      (catch Exception e
                        (println (format "  ERR  %s (%s)" (or title url) (ex-message e)))
                        {:url url :valid? false})))
          valid   (count (filter :valid? results))]
      (println (format "\nTotal: %d  Valid: %d  Invalid: %d"
                       (count results) valid (- (count results) valid))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(let [args *command-line-args*]
  (cond
    (= "--opml" (first args))
    (if-let [path (second args)]
      (validate-opml path)
      (println "Error: --opml requires a path argument"))

    (first args)
    (validate (first args))

    :else
    (do
      (println "Usage:")
      (println "  bb validate-feed.bb <atom-feed-url>")
      (println "  bb validate-feed.bb --opml <path-to.opml>")
      (println)
      (println "Example feeds:")
      (println "  https://xkcd.com/atom.xml")
      (println "  https://github.com/clojure/clojure/releases.atom")
      (println)
      (println "Example OPML:")
      (println "  bb validate-feed.bb --opml test/fixtures/sample.opml"))))
