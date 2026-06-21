# OPML Batch Import

atom-validator supports importing [OPML 2.0](http://opml.org/spec2.opml) feed
lists - the standard exchange format used by every major RSS reader
(Feedly, NewsBlur, Inoreader, NetNewsWire, etc.) - so you can validate an
entire subscription list in one batch.

## Quick Start

```clojure
(require '[atom-validator.opml :as opml]
         '[babashka.http-client :as http])

;; 1. Parse the OPML
(def my-feeds
  (opml/parse-opml (slurp "subscriptions.opml")))

;; => {:title "My Feeds"
;;     :feeds [{:title "xkcd"
;;              :url "https://xkcd.com/atom.xml"
;;              :type "atom"
;;              :html-url "https://xkcd.com/"
;;              :category "Tech"} ...]}

;; 2. Just the URLs
(opml/extract-feed-urls my-feeds)
;; => ["https://xkcd.com/atom.xml" ...]

;; 3. Batch validate (supply your own HTTP client)
(opml/validate-opml-feeds my-feeds
                          {:fetch #(:body (http/get %))})
;; => {:total 42, :valid 38, :invalid 4, :errors [...], :results [...]}
```

## API

### `parse-opml`

```clojure
(parse-opml source)
```

Parse an OPML 2.0 document from a string, reader, or input stream. Returns:

```clojure
{:title "Feed list title"
 :feeds [{:title    "Feed display name"
          :url      "https://example.com/feed.xml"  ; from xmlUrl attribute
          :type     "atom"                          ; "atom"/"rss" if declared
          :html-url "https://example.com/"          ; from htmlUrl attribute
          :category "Tech"}                         ; nearest container outline
         ...]}
```

A feed outline is identified by the presence of an `xmlUrl` attribute, per
the OPML 2.0 spec. Container outlines (with no `xmlUrl`) become `:category`
labels on their descendant feeds. Nesting is arbitrarily deep; the
most-specific container title wins.

### `extract-feed-urls`

```clojure
(extract-feed-urls opml)
```

Return a vector of feed URLs from a parsed OPML map, in document order.

### `validate-opml-feeds`

```clojure
(validate-opml-feeds opml)
(validate-opml-feeds opml opts)
```

Batch validate every feed referenced by the OPML.

**Options:**

| Key          | Type                          | Default                            |
|--------------|-------------------------------|------------------------------------|
| `:fetch`     | `(fn [url] -> feed-string)`   | **required**                       |
| `:validate`  | `(fn [source] -> result-map)` | `atom-validator.core/validate-feed`|
| `:parallel?` | boolean                       | `false`                            |

The library deliberately does **not** depend on any HTTP client. You supply
the fetcher - that lets you use babashka's `http-client`, `clj-http`,
`hato`, or a stub for testing.

**Return value contract:**

```clojure
{:total   10
 :valid   8
 :invalid 2
 :errors  [...]               ; flattened errors across all failed feeds
 :results [{:url      "https://example.com/feed.xml"
            :valid?   true
            :errors   []
            :warnings []}
           ...]}
```

A single fetch failure cannot abort the batch - it is captured per-feed as
an error with `:code :fetch-failed` (or `:fetch-or-parse-failed`).

## Babashka Example

`examples/validate-feed.bb` accepts `--opml` to walk a feed list:

```bash
bb examples/validate-feed.bb --opml test/fixtures/sample.opml
```

## OPML Format Notes

A minimal OPML 2.0 document:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head>
    <title>My Feeds</title>
  </head>
  <body>
    <outline text="Tech" title="Tech">
      <outline type="rss" text="Hacker News"
               xmlUrl="https://hnrss.org/frontpage"
               htmlUrl="https://news.ycombinator.com/"/>
      <outline type="atom" text="xkcd"
               xmlUrl="https://xkcd.com/atom.xml"/>
    </outline>
  </body>
</opml>
```

Key attributes:

- `xmlUrl` - canonical feed URL (REQUIRED for feed outlines)
- `htmlUrl` - human-readable site URL (optional)
- `type` - usually `"rss"` or `"atom"`, but informational only
- `text` - display label (REQUIRED by spec, often duplicated as `title`)
- `title` - optional, preferred over `text` when both are present

Most readers export with both `text` and `title`; we use `:title` first,
falling back to `:text`.

## Where to Get OPML Files

Most readers export under Settings -> Account / Import-Export:

- **Feedly**: Organize -> OPML
- **NewsBlur**: Account -> Import & Export
- **Inoreader**: Preferences -> Subscriptions -> Export
- **NetNewsWire**: File -> Export Subscriptions

See `test/fixtures/sample.opml` for a worked example with categories and
uncategorised feeds.
