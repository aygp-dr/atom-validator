# atom-validator Project Instructions

RFC 4287 Atom feed validator with property-based testing.

## Quick Reference

```bash
make test       # Run unit + property tests
make lint       # clj-kondo static analysis
make check      # lint + test
make security   # lint + nvd CVE scan
make repl       # Start CIDER nREPL on :7888
make release    # Full release: check, tag, deploy, docs
```

## Validation Contracts

### Error Codes

| Code | Issue | Invariant |
|------|-------|-----------|
| `:day-of-week-mismatch` | #1 | Title day-of-week MUST match `<updated>` date |
| `:stale-feed-updated` | #2 | Feed `<updated>` MUST >= max(entry `<updated>`) |
| `:invalid-url-host` | #3 | URLs MUST be valid absolute URLs with real TLDs |
| `:invalid-url` | #3 | URL parsing failed |
| `:invalid-url-scheme` | #3 | Scheme MUST be http/https |
| `:missing-required` | RFC | Required elements per RFC 4287 |

### Return Value Contract

All validation functions return:

```clojure
{:valid?   boolean          ; true iff errors is empty (or empty + warnings if strict?)
 :errors   [{:type :error   ; always :error
             :code keyword  ; one of the codes above
             :message str   ; human-readable
             :path vector}] ; location in feed structure
 :warnings [{:type :warning
             :code keyword
             :message str
             :path vector}]
 :feed     map}             ; parsed feed (validate-feed only)
```

## Property-Based Testing

### Generator → Validator Invariants

These MUST hold for all generated inputs:

| Generator | Property | Test Count |
|-----------|----------|------------|
| `gen-entry-with-mismatched-day` | Result contains `:day-of-week-mismatch` | 20 |
| `gen-entry-with-correct-day` | Result does NOT contain `:day-of-week-mismatch` | 20 |
| `gen-feed-with-stale-updated` | Result contains `:stale-feed-updated` | 10 |
| `gen-entry-with-invalid-url` | Result contains `:invalid-url-host` or `:invalid-url` | 20 |
| `gen-valid-atom-entry` | Result does NOT contain URL errors | 20 |
| `gen-valid-atom-feed` | `(:valid? result)` is true (semantic? false) | 10 |

### Boundary Conditions in Generators

```clojure
;; Date boundaries
gen-year   ; 2020-2030
gen-month  ; 1-12
gen-day    ; 1-28 (safe for all months)

;; Fixed test date for day-of-week tests
"2026-06-19T00:00:00Z"  ; This is a FRIDAY

;; Invalid URL patterns (must end in TLD-like suffix)
["https://wal.shsite/path/"      ; 'shsite' ends in 'site'
 "https://example.compage/x/"    ; 'compage' ends in 'page'
 "https://test.orgpath/y/"       ; 'orgpath' ends in 'path'
 "https://api.ioapp/z/"]         ; 'ioapp' ends in 'app'
```

## Test Structure

```
test/atom_validator/
├── core_test.clj       # Integration + property tests
└── generators.clj      # test.check generators

;; Test naming convention:
;; - deftest: example-based, named after scenario
;; - defspec: property-based, prefixed with prop-
```

### Running Tests

```bash
# All tests
make test

# Specific namespace
clj -X:test :nses '[atom-validator.core-test]'

# With verbose output
clj -X:test :reporter :verbose

# REPL-driven
(require '[clojure.test :refer [run-tests]])
(run-tests 'atom-validator.core-test)
```

## Deployment Validation

### Pre-Deploy Checklist

1. `make check` - All tests pass, no lint errors
2. `bin/validate-deploy` - JAR audit for secrets
3. Version is `0.1.N` where N = git commit count

### Deploy Audit Checks

`bin/validate-deploy` scans JAR for:
- Password/secret/API key patterns
- Hardcoded tokens (CLOJARS_, ghp_, sk-)
- Email addresses (non-git URLs)
- .env, .pem, .key files
- Large base64-encoded strings

### Release Process

```bash
make release
# Runs: check → validate-deploy → git tag → push tag → deploy → cljdoc trigger
```

### Credentials

```bash
# Clojars deploy token (stored in pass)
pass show clojars/apace/deploy-token-hydra

# 2FA code (if needed)
pass otp clojars/apace/totp
```

## Versioning

- Format: `0.1.N` where N = `git rev-list --count HEAD`
- Calculated in `build.clj` via `(b/git-count-revs nil)`
- Tags: `v0.1.N`

## Local Security Scanning

```bash
# First time: install nvd-clojure
clojure -Ttools install nvd-clojure/nvd-clojure '{:mvn/version "RELEASE"}' :as nvd

# Get NVD API key (required for practical use)
# https://nvd.nist.gov/developers/request-an-api-key
# Add to nvd-clojure.edn: {:nvd {:nvd-api {:key "YOUR-KEY"}}}

# Run CVE scan (advisory - continues on failure without API key)
make nvd

# Combined lint + CVE (nvd advisory only)
make security
```

**Note**: Without an API key, first NVD database download (~360K records) is extremely slow due to rate limiting.

## Development Setup

### REPL

```bash
make repl
# Then in Emacs: M-x cider-connect RET localhost RET 7888
```

### FlowStorm Debugging

```bash
make storm
# In REPL: (flow-storm.api/local-connect)
```

### Emacs Org-Mode

See `doc/emacs-setup.org` for:
- ob-clojure configuration
- CIDER + paredit + nyan-mode setup
- Literate programming workflow

## File Responsibilities

| File | Responsibility |
|------|----------------|
| `core.clj` | Public API: `validate-feed`, `validate-entry`, `parse-feed`, `valid?` |
| `parser.clj` | XML → Clojure map via data.xml |
| `rules.clj` | RFC 4287 structural validation |
| `semantic.clj` | Day-of-week matching |
| `url.clj` | URL/IRI validation |
| `generators.clj` | test.check generators |
| `core_test.clj` | Unit + property tests |

## What NOT to Do

- Do not change error code keywords (`:day-of-week-mismatch`, etc.) - they're part of the public API
- Do not remove property tests - they catch edge cases unit tests miss
- Do not deploy without `bin/validate-deploy` passing
- Do not hardcode dates in generators other than the known Friday (2026-06-19)
- Do not skip the JAR audit - secrets in Clojars are permanent

## Updating Contracts

If validation rules change:

1. Update the generator to produce inputs matching the new rule
2. Update/add corresponding `defspec` property test
3. Run `make test` - property tests should pass
4. Update error code table above if new codes added
5. Update `doc/validation-rules.md` for cljdoc

## Screenshots

The `images/04-error-handling.png` shows the canonical development workflow:
- Left pane: Org-mode with executable examples
- Right pane: REPL showing validation results
- Modeline: nyan-mode progress indicator (easter egg)

Capture with: `Xvfb :99`, `scrot`, `magick -crop` (not `-trim` - preserves modeline)
