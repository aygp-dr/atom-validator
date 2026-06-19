# Changelog

All notable changes to this project will be documented in this file.

## [0.1.36] - 2026-06-19

### Added
- cljdoc articles: Getting Started, Validation Rules
- Emacs screenshot with nyan-mode
- Full hypermedia linking in README

### Fixed
- Support for `tag:` URIs per RFC 4151 (GitHub releases use these)
- Missing `clojure.java.io` require in core namespace
- CI badge rendering (added .svg extension)

## [0.1.27] - 2026-06-19

### Added
- Version string read from POM at runtime
- CodeQL security scanning
- Dependabot dependency updates
- Test coverage with cloverage

### Fixed
- Clojars deploy with correct group scope

## [0.1.16] - 2026-06-18

### Added
- Initial release to Clojars
- RFC 4287 structural validation
- Semantic validation (day-of-week, feed freshness)
- URL/IRI validation
- Property-based testing with test.check generators
- CIDER and FlowStorm REPL support

## [Unreleased]

### Planned
- XML namespace prefix handling
- Atom extensions validation
- Feed autodiscovery link validation
