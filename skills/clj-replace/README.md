# clj-replace

**Structural Clojure code replacement that ignores formatting differences.**

When `str_replace` fails because whitespace changed (e.g., after running `cljfmt`), clj-replace succeeds by comparing code **structure** instead of text.

## What's the Problem?

```clojure
; After cljfmt, your file looks like this:
(defn handler [req] (let [body "Hello"] {:body body
                                         :status 200}))
```

You want to rename `req` to `request`, but:
- ❌ Your editor's find-and-replace can't find the pattern (formatting changed)
- ❌ `str_replace` fails: "old_string not found"
- ✅ **clj-replace works** — it understands the code structure

## Quick Start

```bash
bb /path/to/clj-replace.bb your-file.clj "req" "request"
```

Result:
```
✓ Replaced 1 node(s) in your-file.clj
  Location: line 1, column 22
  Old: req
  New: request
```

See [`docs/QUICK-START.md`](docs/QUICK-START.md) for detailed examples.

## How It Works

- **Parses** both old-string and file into S-expression trees
- **Compares** structure, not text (ignores whitespace/indentation/newlines)
- **Replaces** matching nodes while preserving original formatting
- **Reports** exact location (line + column)

## Installation

Copy `scripts/clj-replace.bb` to a location in your PATH, or reference it directly.

## Usage

```
bb clj-replace.bb <filename> <old-string> <new-string>
```

**Parameters:**
- `filename` — Path to Clojure file
- `old-string` — Code to find (as a string)
- `new-string` — Code to replace with (as a string)

**Exit codes:**
- `0` — Success
- `1` — Parse error (invalid Clojure or file not found)
- `2` — No match found
- `3` — Multiple matches found (ambiguous)
- `4` — old-string is in the file but cannot be matched structurally (e.g. `#()`, `##NaN`); use a plain text edit

See [`SKILL.md`](SKILL.md) for full documentation.

## Regression Tests

`test/cases.edn` holds common and edge cases for the script; `test/run.bb`
runs each one against `scripts/clj-replace.bb` and checks the exit code and the
resulting file. From the repository root:

```bash
bb test                                   # all cases
bb skills/clj-replace/test/run.bb nan     # selected case ids
CLJ_REPLACE_SCRIPT=/path/to/other.bb bb test   # another implementation
```

Cases marked `:known-bug true` describe correct behaviour the script does not
have yet; they count as expected failures (XFAIL). When one starts passing the
run fails with XPASS, so remove its marker.

## Examples

### Example 1: Format-Agnostic Rename

**Before:**
```clojure
(defn process [data]
  (transform data))
```

**After formatting (cljfmt):**
```clojure
(defn process
  [data]
  (transform data))
```

**Command:**
```bash
bb clj-replace.bb file.clj "data" "parsed-data"
```

Works even though formatting changed! ✅

### Example 2: Multi-line Expression

**File:**
```clojure
(some-function
  arg1
  arg2
  arg3)
```

**Command:**
```bash
bb clj-replace.bb file.clj "(some-function arg1 arg2 arg3)" "(other-function arg1 arg2 arg3)"
```

Matches regardless of line breaks and indentation. ✅

## When to Use clj-replace

- ✅ After running formatters (`cljfmt`, `zprint`, etc.)
- ✅ Renaming function parameters or local bindings
- ✅ Multi-file structural refactoring
- ✅ When you need to be sure about scope (ambiguity detection)
- ✅ Large codebases where text tools fail

## Implementation

Built on:
- **rewrite-clj** — Format-preserving Clojure parsing and modification
- **babashka** — Lightweight Clojure scripting

## Documentation

- **[`SKILL.md`](SKILL.md)** — Full technical documentation
- **[`docs/QUICK-START.md`](docs/QUICK-START.md)** — Getting started with examples
- **[`docs/VERIFICATION.md`](docs/VERIFICATION.md)** — How to verify the tool works
- **[`evals/evals.json`](evals/evals.json)** — Test cases you can run

## License

Same as clj-native-agent project.

---

**Have a question or found a bug?** Check the docs or run the verification tests.
