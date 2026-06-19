# Validation Rules

atom-validator implements validation rules based on [RFC 4287](https://datatracker.ietf.org/doc/html/rfc4287) plus additional semantic checks.

## Structural Validation (RFC 4287)

### Required Elements

Per RFC 4287 Section 4.1.1, every Atom feed must contain:

- `atom:id` - Permanent, universally unique identifier (IRI)
- `atom:title` - Human-readable title
- `atom:updated` - Most recent modification time (RFC 3339)

Each entry must contain:

- `atom:id` - Permanent, universally unique identifier
- `atom:title` - Human-readable title
- `atom:updated` - Most recent modification time

### Date Format

All dates must be valid [RFC 3339](https://datatracker.ietf.org/doc/html/rfc3339) timestamps:

```
2026-06-19T12:00:00Z
2026-06-19T08:00:00-04:00
```

### Valid Identifiers

The `atom:id` element accepts:

- **URN**: `urn:uuid:550e8400-e29b-41d4-a716-446655440000`
- **Tag URI** ([RFC 4151](https://datatracker.ietf.org/doc/html/rfc4151)): `tag:github.com,2008:Repository/123`
- **HTTP(S) URL**: `https://example.com/feed/entry-1`

## Semantic Validation

### Feed Freshness (`:stale-feed-updated`)

The feed's `<updated>` timestamp should be >= the most recent entry's `<updated>`.

**Why?** Aggregators use the feed's updated timestamp to determine if they need to re-fetch. A stale timestamp causes entries to be missed.

```clojure
;; BAD: Feed older than entry
{:updated "2026-06-14T00:00:00Z"
 :entries [{:updated "2026-06-19T00:00:00Z" ...}]}

;; GOOD: Feed reflects latest entry
{:updated "2026-06-19T00:00:00Z"
 :entries [{:updated "2026-06-19T00:00:00Z" ...}]}
```

### Day-of-Week Mismatch (`:day-of-week-mismatch`)

If an entry title mentions a day of the week, it should match the `<updated>` date.

**Why?** Newsletter titles like "Morning Brief: Thursday, June 19" create confusion when the actual date is a different day.

```clojure
;; BAD: Title says Thursday, but 2026-06-19 is Friday
{:title "Morning Brief: Thursday, June 19"
 :updated "2026-06-19T00:00:00Z"}

;; GOOD: Correct day
{:title "Morning Brief: Friday, June 19"
 :updated "2026-06-19T00:00:00Z"}
```

This check can be disabled:

```clojure
(v/validate-feed feed {:semantic? false})
```

## URL Validation

### Invalid Host (`:invalid-url-host`)

Entry link URLs are checked for:

- Valid scheme (`http` or `https`)
- Valid hostname (proper TLD, no typos)
- Suspicious patterns like merged host/path (`wal.shsite` instead of `wal.sh/site`)

```clojure
;; BAD: Typo merged host and path
{:links [{:href "https://wal.shsite/post/1"}]}

;; GOOD: Proper URL
{:links [{:href "https://wal.sh/site/post/1"}]}
```

## Property-Based Testing

All validation rules are tested with [test.check](https://github.com/clojure/test.check) generators:

```clojure
(require '[atom-validator.generators :as g]
         '[clojure.test.check.generators :as gen])

;; Generate feeds with specific issues for testing
(gen/generate (g/gen-feed-with-stale-updated))
(gen/generate (g/gen-entry-with-mismatched-day))
(gen/generate (g/gen-entry-with-invalid-url))
```
