# Decisions

## 2026-11-19 — clj-replace regression tests as data-driven black-box cases

Before: clj-replace had only `evals/evals.json`, which checks whether an LLM chooses the skill, not whether `scripts/clj-replace.bb` matches correctly. Issue #3 (`#()` never matches) showed the script itself had no tests.
Decision: Add `skills/clj-replace/test/` beside `scripts/` (not inside it, so the agent does not see fixtures among the tools it runs): `cases.edn` holds one map per case (file, old, new, expected exit and file), and `run.bb` runs the script as a subprocess for each case. Cases for bugs not fixed yet carry `:known-bug true` and count as expected failures; one that passes fails the run (XPASS). A root `bb.edn` adds `bb test`. `CLJ_REPLACE_SCRIPT` points the runner at another implementation.
Reason: Issue #3 offers two fixes (normalise gensyms vs compare rewrite-clj nodes) and probing found ten more cases of the same root cause, `(= (z/sexpr a) (z/sexpr b))` not meaning "same source": false negatives (`##NaN`, `#()` in syntax-quote) and silent false positives (metadata, `#_`, vector vs list, `1` vs `1N`). Black-box cases let both fixes run against one suite: the gensym-normalising prototype turns 4 of the 11 known bugs into XPASS and leaves 7.
