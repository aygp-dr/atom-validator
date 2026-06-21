# HTTP Feed Validation

The `atom-validator.http` namespace adds the ability to fetch feeds over HTTP
and pass them straight into the validator. It uses `java.net.http` (built into
the JDK; no extra dependencies) and is also wired into
`atom-validator.core/validate-feed` so URL strings are auto-fetched.

## Quick start

```clojure
(require '[atom-validator.core :as v])

;; Fetch and validate in one call
(v/validate-feed "https://example.com/feed.xml")
;; => {:valid? true
;;     :errors []
;;     :warnings [...]
;;     :feed {...}
;;     :http {:url "https://example.com/feed.xml"
;;            :status 200
;;            :content-type "application/atom+xml"
;;            :redirects []}}
```

If the input is not a URL, behavior is unchanged from previous versions:

```clojure
(v/validate-feed "<feed>...</feed>")  ;; same as before
(v/validate-feed parsed-map)          ;; same as before
```

## Direct HTTP access

```clojure
(require '[atom-validator.http :as http])

(http/fetch-feed "https://example.com/feed.xml")
;; => {:ok? true
;;     :url "https://example.com/feed.xml"
;;     :status 200
;;     :content-type "application/atom+xml"
;;     :body "<feed>...</feed>"
;;     :redirects []
;;     :errors []}

(http/fetch-and-validate "https://example.com/feed.xml")
;; equivalent to: (v/validate-feed "https://example.com/feed.xml")
```

## Defaults

| Setting          | Default                                                            |
|------------------|--------------------------------------------------------------------|
| User-Agent       | `atom-validator/0.2.0 (+https://github.com/aygp-dr/atom-validator)` |
| Timeout          | 30 seconds                                                          |
| Max redirects    | 5                                                                  |
| Content-Type check | Enabled                                                          |

Accepted feed Content-Type values (parameters like `charset=utf-8` are
ignored, comparison is case-insensitive):

- `application/atom+xml`
- `application/rss+xml`
- `application/feed+json`
- `application/xml`
- `text/xml`
- `application/json`
- `text/json`

## Options

All HTTP options can be passed in the same options map as the existing
`validate-feed` options.

| Option                    | Default | Description                                                |
|---------------------------|---------|------------------------------------------------------------|
| `:timeout-seconds`        | 30      | Per-request timeout                                        |
| `:max-redirects`          | 5       | Max redirect hops before `:max-redirects-exceeded`         |
| `:validate-content-type?` | true    | Reject responses whose Content-Type is not a feed type     |
| `:head-check?`            | false   | Issue HEAD first; if it redirects, follow without GET      |
| `:fetch?`                 | true    | (core/validate-feed only) Disable URL auto-fetching        |

```clojure
(v/validate-feed "https://example.com/feed.xml"
                 {:timeout-seconds 10
                  :max-redirects 3
                  :semantic? false})
```

## Error codes

All errors follow the standard validator contract:
`{:type :error :code <keyword> :message <str>}`.

| Code                       | Cause                                                       |
|----------------------------|-------------------------------------------------------------|
| `:invalid-url`             | URL is not absolute http/https or cannot be parsed          |
| `:http-error`              | Network failure or non-2xx HTTP status                      |
| `:http-timeout`            | Request exceeded `:timeout-seconds`                         |
| `:invalid-content-type`    | Response Content-Type is not one of the feed MIME types     |
| `:max-redirects-exceeded`  | Redirect chain longer than `:max-redirects`                 |

When a fetch fails, the result map has `:valid? false` and an `:http`
metadata map describing what happened:

```clojure
(v/validate-feed "https://example.com/missing")
;; => {:valid? false
;;     :errors [{:type :error :code :http-error :message "HTTP 404 ..."}]
;;     :warnings []
;;     :http {:url "https://example.com/missing"
;;            :status 404
;;            :content-type "text/plain"
;;            :redirects []}}
```

## Redirects

Redirects (301, 302, 303, 307, 308) are followed up to `:max-redirects`.
Each intermediate URL is recorded in `:redirects`. Relative `Location` headers
are resolved against the previous URL.

```clojure
(:redirects (http/fetch-feed "https://example.com/old-feed-url"))
;; => ["https://example.com/old-feed-url"
;;     "https://example.com/redirected-once"]
```

If the redirect chain exceeds the limit:

```clojure
(http/fetch-feed "https://loop.example.com/" {:max-redirects 3})
;; => {:ok? false
;;     :errors [{:code :max-redirects-exceeded :message "..."}]
;;     ...}
```

## Skipping the fetch

If you have a URL-shaped string that should NOT be fetched (e.g. you are
testing parser error handling), set `:fetch? false`:

```clojure
(v/validate-feed url-string {:fetch? false})
```

## Testing locally

The test suite at `test/atom_validator/http_test.clj` spins up an in-process
`com.sun.net.httpserver.HttpServer` on an ephemeral port and registers
per-test handlers. This avoids any reliance on the public internet and keeps
the tests fast and deterministic.
