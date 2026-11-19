# clj-replace Changelog

## [2.0.0] - 2026-11-19

### Changed (breaking)
- Forms are compared ignoring layout only (whitespace, indentation, line
  breaks, commas). A form written differently no longer matches, even with
  the same value: metadata and type hints, `#_` forms, map and set order,
  `'x` vs `(quote x)`, `#'a` vs `(var a)`, number spelling (`0xFF` vs
  `255`, `1` vs `1N`), string escapes, vector vs list, and comments inside
  the form. Previously these matched through `sexpr` equality, and replacing
  could silently drop metadata, comments or `#_` code.
- `old-string` must be exactly one form with no top-level comment, and
  `new-string` exactly one form or blank (delete); otherwise exit 1.
  Previously only the first form was used and the rest silently ignored.

### Added
- Exit code `4`, near match: no form is written like `old-string` but some
  have the same value; their locations and exact text are printed so the
  caller can retry with that text.
- Exit code table with the next step for each code in `SKILL.md`.
- Regression tests (`test/cases.edn`, `test/run.bb`, `bb test`) and a
  corpus property test (`test/corpus.bb`, `bb corpus`).

### Fixed
- Forms containing `#()` never matched, not even themselves (#3); the same
  for `##NaN`.
- An `old-string` that is a `#_` form crashed with a stack trace.

## [1.0.0] - 2026-05-29

### Initial Release
- Replace Clojure or Babashka code with S-expression structure comparison
- Handle formatting differences gracefully
- Compare code semantically rather than syntactically
- More reliable than string-based replacement
