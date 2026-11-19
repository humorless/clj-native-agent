# clj-replace

**Structural Clojure code replacement that ignores formatting differences.**

When `str_replace` fails because whitespace changed (e.g., after running `cljfmt`), clj-replace succeeds by comparing forms while ignoring **layout only** (whitespace, indentation, line breaks, commas).

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

- **Parses** old-string, new-string and the file with rewrite-clj
- **Compares** forms ignoring layout only; tokens, strings, reader syntax, metadata, `#_` forms, map order and comments must be written the same way
- **Shows** the file's exact text when a form has the same value but is written differently (exit 4), so you can retry with it
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

**Exit codes** (the file is modified only on `0`):
- `0` — Replaced exactly one form (a blank new-string deletes it)
- `1` — Invalid input: missing arguments, file not found, parse error, or old/new-string is not exactly one form
- `2` — No match
- `3` — Several forms match (ambiguous)
- `4` — Near match: same value written differently; the file's text is printed so you can retry with it

See the exit code table in [`SKILL.md`](SKILL.md#exit-codes) for what to do after each one.

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

`test/corpus.bb` is a property test over real code: it samples forms from a
Clojure corpus, relayouts each one, and checks that clj-replace finds exactly
that form. It needs a corpus on disk, so it is not part of `bb test`:

```bash
git clone --depth 1 https://github.com/jonase/eastwood /tmp/eastwood
bb corpus --sample 300 /tmp/eastwood
```

`test/bench.bb` guards performance. It generates its own fixtures, times
each scenario (median of several runs) and fails when the script is more than
1.3x slower than a baseline script, or when time grows faster than the input
(a 4x larger file, or deeply nested forms):

```bash
bb bench                                   # scaling checks only
bb bench --baseline /path/to/old/clj-replace.bb
```

`test/mutate.bb` measures how strong the cases are. It makes one mutant of the
script per mutation point (swapped operators such as `=`/`not=` and
`when`/`when-not`, integers + 1, strings emptied), runs the cases against each
(stopping at the first failure), and lists the mutants that survive:

```bash
bb mutate                  # about a minute on 8 cores
bb mutate --limit 20       # a quick sample
```

A survivor is either a gap in the cases or an equivalent mutant (same
behaviour). Survivors a person has judged equivalent are recorded, with the
reason, in `test/mutate-equivalent.edn`; `bb mutate` lists only new survivors
as needing attention and exits 1 for them (or for runs that failed for another
reason, which are not scored). Currently 111 of 123 mutants are killed and the
12 survivors are recorded as equivalent; two of them only change speed, which
`bb bench` rejects.

`bb lint` runs clj-kondo and cljfmt on the same files CI checks. CI
(`.github/workflows/clj-replace.yml`) runs `bb lint` and `bb test` on every
pull request and push to main, and `bb bench` against the base branch's
script on pull requests.

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
