#!/usr/bin/env bb
;; Babashka script for validating Atom feeds
;; Usage: bb validate-feed.bb <url>

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

(when-let [url (first *command-line-args*)]
  (validate url))

(println "\nUsage: bb validate-feed.bb <atom-feed-url>")
(println "Example feeds:")
(println "  https://xkcd.com/atom.xml")
(println "  https://github.com/clojure/clojure/releases.atom")
