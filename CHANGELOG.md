# Changelog

All notable changes to this project will be documented in this file.

## [0.1.53] - 2026-06-21

### Added
- **RSS 2.0 support** (`atom_validator.rss`) - parse and validate RSS 2.0 feeds
- **JSON Feed 1.1 support** (`atom_validator.jsonfeed`) - parse and validate JSON feeds
- **Schematron rules** (`schemas/atom.sch`) - cross-element validation
- **Auto-detection** - `validate-feed` auto-detects Atom/RSS/JSON Feed
- **XML validation tooling** - Atom RelaxNG schema (RFC 4287 Appendix B)
- `make tools` - downloads jing/trang for RelaxNG validation
- `make nvd` and `make security` - CVE scanning with nvd-clojure
- Test fixtures: `valid-rss.xml`, `feed.json`, real-world feeds
- `MILESTONES.org` - feature roadmap
- `.claude/CLAUDE.md` - project contracts and testing methodology
- 6 numbered X11 Emacs screenshots with nyan-mode
- Emacs setup guide (`doc/emacs-setup.org`)

### Changed
- Replaced CodeQL workflow with clj-kondo + nvd-clojure (CodeQL doesn't support Clojure)
- `core.clj` now routes to format-specific validators

### Fixed
- nyan-mode modeline preserved in screenshots (use `magick -crop` not `-trim`)
- cljdoc badge uses shields.io (native badge 404s for dotted group IDs)

### Stats
- Tests: 64 (was 14)
- Assertions: 99 (was 17)
- Formats supported: 3 (Atom, RSS, JSON Feed)

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
