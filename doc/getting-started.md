# Getting Started

## Installation

Add to your `deps.edn`:

```clojure
{:deps {org.clojars.apace/atom-validator {:mvn/version "RELEASE"}}}
```

Or Leiningen `project.clj`:

```clojure
[org.clojars.apace/atom-validator "RELEASE"]
```

## Basic Usage

```clojure
(require '[atom-validator.core :as v])

;; Validate a feed map
(v/validate-feed {:id "urn:uuid:feed-1"
                  :title "My Blog"
                  :updated "2026-06-19T12:00:00Z"
                  :entries []})
;; => {:valid? true :errors [] :warnings [] :feed {...}}

;; Quick validity check
(v/valid? feed)
;; => true
```

## Parsing XML

```clojure
;; Parse from XML string
(def feed (v/parse-feed "<feed xmlns='http://www.w3.org/2005/Atom'>...</feed>"))

;; Parse from URL
(def feed (v/parse-feed (slurp "https://example.com/feed.atom")))

;; Then validate
(v/validate-feed feed)
```

## Validation Options

```clojure
;; Disable semantic checks (day-of-week validation)
(v/validate-feed feed {:semantic? false})

;; Strict mode: treat warnings as errors
(v/validate-feed feed {:strict? true})
```

## Entry Validation

```clojure
;; Validate individual entries
(v/validate-entry {:id "urn:uuid:entry-1"
                   :title "Hello World"
                   :updated "2026-06-19T00:00:00Z"
                   :links [{:href "https://example.com/post/1" :rel "alternate"}]})
```

## Error Codes

| Code | Description |
|------|-------------|
| `:missing-id` | Feed/entry missing required `<id>` |
| `:missing-title` | Feed/entry missing required `<title>` |
| `:missing-updated` | Feed/entry missing required `<updated>` |
| `:invalid-date` | Date not in RFC 3339 format |
| `:stale-feed-updated` | Feed `<updated>` older than newest entry |
| `:day-of-week-mismatch` | Title mentions wrong day of week |
| `:invalid-url-host` | URL has suspicious/invalid hostname |

## Next Steps

- See [Validation Rules](validation-rules.md) for detailed rule explanations
- Check the [API Reference](https://cljdoc.org/d/org.clojars.apace/atom-validator/CURRENT/api/atom-validator.core)
