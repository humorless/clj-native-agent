# clj-replace Changelog

## [Unreleased]

### Added
- Exit code `4`: old-string appears in the file (up to whitespace) but the
  structural comparison does not match it, e.g. forms containing `#()` or
  `##NaN` (#3). The message says this is a tool limitation and to use a plain
  text edit, instead of the misleading "No matching S-expression found".
- Regression tests: `test/cases.edn` and `test/run.bb`, run with `bb test`.

## [1.0.0] - 2026-05-29

### Initial Release
- Replace Clojure or Babashka code with S-expression structure comparison
- Handle formatting differences gracefully
- Compare code semantically rather than syntactically
- More reliable than string-based replacement
