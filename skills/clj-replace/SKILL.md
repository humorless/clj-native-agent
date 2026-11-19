---
name: clj-replace
version: 1.0.0
description: Use when replacing Clojure or Babashka code but str_replace fails due to formatting differences - matches one form while ignoring only layout (whitespace, commas, line breaks)
---

# clj-replace: Structural Clojure Code Replacement

A Clojure-aware code replacement tool that improves on Claude Code's built-in `str_replace`: it finds the one form in the file that is written like `old-string` up to layout (whitespace, indentation, line breaks, commas), and replaces it while keeping the rest of the file's formatting.

## When to Use

Use `/clj-replace` when:

- Claude Code's `str_replace` fails because formatting (whitespace, indentation, line breaks) differs between the `old_string` and target code
- You need to replace code but the exact whitespace/formatting doesn't match the source
- You want replacements to preserve the original file's formatting and style

**Example:** If `str_replace` fails with "old_string not found" but the code structure looks identical, switch to `clj-replace`.

## How It Works

1. Parses `old-string`, `new-string` and the file with rewrite-clj.
2. Walks every form in the file and compares it with `old-string` **ignoring layout only**: whitespace, indentation, line breaks and commas between tokens.
3. Everything else must be written the same way: tokens (`0xFF` is not `255`, `1` is not `1N`), strings (including the whitespace inside them), reader syntax (`'x` is not `(quote x)`, `#'a` is not `(var a)`), metadata and type hints, `#_` forms, map and set order, and comments inside the form (only trailing whitespace on a comment line is ignored).
4. When exactly one form matches, it is replaced with `new-string`; the rest of the file keeps its formatting.
5. When none matches but some forms have the **same value** written differently (different spelling, comments or metadata), clj-replace prints their exact text so you can retry with it (exit 4).

These are all equivalent:
```clojure
(foo bar)          ≡ (foo  bar)        ≡ (foo
                                           bar)
{:a 1, :b 2}       ≡ {:a 1 :b 2}
```

These are **not** (exit 4 shows the file's text):
```clojure
(defn ^:private f [] 1)   vs (defn f [] 1)
{:a 1 :b 2}               vs {:b 2 :a 1}
(or a ; why               vs (or a b)
    b)
```

## Parameters

**Positional Arguments:**

1. `filename` — Path to the Clojure source file to modify
2. `old-string` — Code snippet to find (as a string)
3. `new-string` — Code snippet to replace it with (as a string)

`old-string` must contain exactly one form, with no top-level comment. `new-string` must contain exactly one form, or be blank to delete the matched form. Anything else is rejected (exit 1).

## Usage

```
/clj-replace path/to/file.clj "old code here" "new code here"
```

## Examples

### Example 1: Whitespace Variation

**File content:**
```clojure
(defn update-user
  [db id attrs]
  (merge-user db id attrs))
```

**Command:**
```
/clj-replace src/app.clj "(merge-user db id attrs)" "(update-user-in-db db id attrs)"
```

Works even though file has different indentation. Replaces the exact form while preserving file's style.

### Example 2: Multi-line Form

**File content:**
```clojure
(some-function
  arg1
  arg2
  arg3)
```

**Command:**
```
/clj-replace src/app.clj "(some-function arg1 arg2 arg3)" "(other-function arg1 arg2 arg3)"
```

Matches regardless of how the original is formatted across lines.

## Exit Codes

The file is modified only on exit `0`.

| Exit | Meaning | What to do next |
|---|---|---|
| `0` | Exactly one form matched up to layout and was replaced (or deleted, for a blank `new-string`) | Done |
| `1` | Invalid input: missing arguments, file not found, `old-string` or `new-string` does not parse, or is not exactly one form (`new-string` may also be blank) | Fix the arguments |
| `2` | No match: no form is written like `old-string`, and none has the same value | `old-string` is wrong: re-read the file |
| `3` | Ambiguous: several forms match; their locations are listed | Make `old-string` an enclosing form so it matches one place |
| `4` | Near match: no form is written like `old-string`, but the listed forms have the same value and differ in spelling, comments or metadata; their exact text is printed | Retry with the printed text as `old-string`; keep its comments and metadata in `new-string` unless you mean to remove them |

## Output

On success:
```
✓ Replaced 1 node(s) in path/to/file.clj
  Location: line 42, column 5
  Old: (merge-user db id attrs)
  New: (update-user-in-db db id attrs)
```

If no form is written like `old-string` but some have the same value (exit 4):
```
✗ Error: No exact match in path/to/file.clj, but these forms have the same value and differ in spelling, comments or metadata:
  line 12, column 1:
(defn ^:private f [] 1)
Retry with the file's text above as old-string; keep its comments and metadata in new-string if you mean to.
```

If multiple matches:
```
✗ Error: Found 3 matching S-expressions in path/to/file.clj (ambiguous)
  1. line 42, column 5: (foo bar)
  2. line 85, column 10: (foo bar)
  3. line 120, column 1: (foo bar)
Please provide a more specific old-string (e.g., include surrounding context)
```

## Implementation Details

Uses `rewrite-clj` for format-preserving parsing and replacement. Each form in the file is compared with `old-string` as a rewrite-clj node tree: same tag, same delimiters, and the same non-layout children in order; tokens, strings and comments compare by source text (comments up to trailing whitespace). Layout nodes (whitespace, newlines, commas) are skipped. The near-match check (exit 4) compares values with `sexpr` and `=` (with `#()` gensyms renamed); it only chooses the error message and never edits the file.
