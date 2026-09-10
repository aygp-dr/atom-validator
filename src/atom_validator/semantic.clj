(ns atom-validator.semantic
  "Semantic validation checks beyond RFC 4287 structure.
   These detect logical inconsistencies in feed content."
  (:require [atom-validator.rules :as rules]
            [atom-validator.specs :as specs]
            [clj-time.core :as t]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(def day-names
  "Mapping of day-of-week numbers (1=Monday) to name patterns."
  {1 #{"monday" "mon"}
   2 #{"tuesday" "tue" "tues"}
   3 #{"wednesday" "wed"}
   4 #{"thursday" "thu" "thurs" "thur"}
   5 #{"friday" "fri"}
   6 #{"saturday" "sat"}
   7 #{"sunday" "sun"}})

(def day-pattern
  "Regex to find day names in text."
  #"(?i)\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|thur|fri|sat|sun)\b")

(defn extract-day-from-title
  "Extract day-of-week name from title if present.
   Returns {:day-name 'thursday' :normalized 4} or nil."
  [title]
  (when-let [match (re-find day-pattern (or title ""))]
    (let [day-name (str/lower-case (second match))
          day-num (first (for [[num names] day-names
                               :when (contains? names day-name)]
                           num))]
      {:day-name (second match)
       :day-num day-num})))

(s/fdef extract-day-from-title
  :args (s/cat :title (s/nilable ::specs/text))
  :ret (s/nilable ::specs/title-day)
  :fn (fn [{{:keys [title]} :args ret :ret}]
        (or (nil? ret)
            (str/includes? (str/lower-case title) (str/lower-case (:day-name ret))))))

(defn get-day-of-week
  "Get day of week (1=Monday, 7=Sunday) from a datetime."
  [dt]
  (when dt
    (t/day-of-week dt)))

(s/fdef get-day-of-week
  :args (s/cat :dt (s/nilable ::specs/datetime))
  :ret (s/nilable (s/int-in 1 8)))

(defn validate-day-of-week
  "Check that day-of-week in title matches the updated date.
   This is Issue #1 from the validator requirements.

   Example: 'Morning Brief: Thursday, June 19' with updated='2026-06-19'
   should fail because June 19, 2026 is a Friday, not Thursday."
  [entry idx]
  (let [title (:title entry)
        updated-dt (rules/parse-datetime (:updated entry))
        title-day (extract-day-from-title title)
        actual-day (get-day-of-week updated-dt)]
    (when (and title-day actual-day
               (not= (:day-num title-day) actual-day))
      (let [actual-name (-> actual-day day-names first str/capitalize)]
        [{:type :error
          :code :day-of-week-mismatch
          :message (str "Title says '" (:day-name title-day)
                        "' but updated date is a " actual-name)
          :path [:entries idx :title]
          :expected actual-name
          :found (:day-name title-day)}]))))

(s/fdef validate-day-of-week
  :args ::specs/entry-rule-args
  :ret ::specs/issues
  :fn (s/and specs/issues-under-entry
             ;; :expected names the actual weekday of :updated, in full
             (fn [{{:keys [entry]} :args ret :ret}]
               (every? #(= (:expected %)
                           (specs/weekday-name
                            (get-day-of-week (rules/parse-datetime (:updated entry)))))
                       ret))))

(defn validate-entry-semantics
  "Run all semantic checks on an entry."
  [entry idx]
  (concat
   (validate-day-of-week entry idx)))

(s/fdef validate-entry-semantics
  :args ::specs/entry-rule-args
  :ret ::specs/issues
  :fn specs/issues-under-entry)

(defn validate-feed-semantics
  "Run semantic checks on entire feed.
   Returns {:errors [...] :warnings [...]}."
  [feed]
  (let [issues (mapcat (fn [[idx entry]]
                         (validate-entry-semantics entry idx))
                       (map-indexed vector (:entries feed)))]
    {:errors (vec (filter #(= :error (:type %)) issues))
     :warnings (vec (filter #(= :warning (:type %)) issues))}))

(s/fdef validate-feed-semantics
  :args ::specs/feed-rule-args
  :ret ::specs/issue-buckets)
