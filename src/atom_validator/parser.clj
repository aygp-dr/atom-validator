(ns atom-validator.parser
  "XML parsing for Atom feeds using clojure.data.xml."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.io StringReader]))

(def atom-ns "http://www.w3.org/2005/Atom")

(defn- local-name
  "Extract local name from a potentially namespaced keyword."
  [kw]
  (let [n (name kw)]
    (if (str/includes? n "/")
      (second (str/split n #"/"))
      n)))

(defn- find-children
  "Find child elements by local name."
  [element local-tag-name]
  (filter #(and (map? %)
                (= (local-name (:tag %)) local-tag-name))
          (:content element)))

(defn- find-child
  "Find first child element by local name."
  [element local-tag-name]
  (first (find-children element local-tag-name)))

(defn- text-content
  "Extract text content from an element."
  [element]
  (when element
    (str/join "" (filter string? (:content element)))))

(defn- parse-link
  "Parse a link element into a map."
  [link-el]
  {:href (get-in link-el [:attrs :href])
   :rel (get-in link-el [:attrs :rel] "alternate")
   :type (get-in link-el [:attrs :type])
   :hreflang (get-in link-el [:attrs :hreflang])
   :title (get-in link-el [:attrs :title])
   :length (get-in link-el [:attrs :length])})

(defn- parse-person
  "Parse an author or contributor element."
  [person-el]
  {:name (text-content (find-child person-el "name"))
   :uri (text-content (find-child person-el "uri"))
   :email (text-content (find-child person-el "email"))})

(defn- parse-category
  "Parse a category element."
  [cat-el]
  {:term (get-in cat-el [:attrs :term])
   :scheme (get-in cat-el [:attrs :scheme])
   :label (get-in cat-el [:attrs :label])})

(defn- parse-content
  "Parse content element, handling different types."
  [content-el]
  (when content-el
    {:type (get-in content-el [:attrs :type] "text")
     :src (get-in content-el [:attrs :src])
     :value (text-content content-el)}))

(defn parse-entry
  "Parse an Atom entry element into a Clojure map."
  [entry-el]
  {:id (text-content (find-child entry-el "id"))
   :title (text-content (find-child entry-el "title"))
   :updated (text-content (find-child entry-el "updated"))
   :published (text-content (find-child entry-el "published"))
   :summary (text-content (find-child entry-el "summary"))
   :content (parse-content (find-child entry-el "content"))
   :authors (mapv parse-person (find-children entry-el "author"))
   :contributors (mapv parse-person (find-children entry-el "contributor"))
   :links (mapv parse-link (find-children entry-el "link"))
   :categories (mapv parse-category (find-children entry-el "category"))
   :rights (text-content (find-child entry-el "rights"))
   :source (find-child entry-el "source")})

(defn parse-feed
  "Parse an Atom feed from XML string or input stream.
   Returns a map with :id, :title, :updated, :entries, etc."
  [source]
  (let [xml-source (if (string? source)
                     (StringReader. source)
                     source)
        root (xml/parse xml-source)]
    {:id (text-content (find-child root "id"))
     :title (text-content (find-child root "title"))
     :subtitle (text-content (find-child root "subtitle"))
     :updated (text-content (find-child root "updated"))
     :rights (text-content (find-child root "rights"))
     :icon (text-content (find-child root "icon"))
     :logo (text-content (find-child root "logo"))
     :generator (text-content (find-child root "generator"))
     :authors (mapv parse-person (find-children root "author"))
     :contributors (mapv parse-person (find-children root "contributor"))
     :links (mapv parse-link (find-children root "link"))
     :categories (mapv parse-category (find-children root "category"))
     :entries (mapv parse-entry (find-children root "entry"))}))
