# clj-replace Changelog

## [Unreleased]

### Added
- Regression tests: `test/cases.edn` and `test/run.bb`, run with `bb test`.

## [1.0.0] - 2026-05-29

### Initial Release
- Replace Clojure or Babashka code with S-expression structure comparison
- Handle formatting differences gracefully
- Compare code semantically rather than syntactically
- More reliable than string-based replacement
