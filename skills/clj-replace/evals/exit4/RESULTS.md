# Exit 4 retry eval: results

Run on 2026-11-20 against clj-replace 2.0.0 (with the Placeholder fix), following
`skills/clj-skill-eval`: the same task with and without the skill.

## Protocol

- Three scenarios in `scenarios.edn`. In each, the first call's old-string has
  the same value as a form in the file but is written differently, so
  clj-replace answers exit 4 with the file's text:
  - `metadata`: the file has `(defn ^:private parse-port ...)`, old-string omits `^:private`
  - `comment`: the file's `total` has an inner `;; prices are stored in cents` comment
  - `map-order`: the file's map lists `:port` before `:host`
- Each run gets its own directory with `target.clj`, `clj-replace.bb` (the
  logging stand-in `clj-replace-logged.bb`) and `real-clj-replace.bb`. The task
  (`prompt.txt`) names the change, gives the first call's two strings, and
  forbids editing the file any other way or reading outside the directory.
- **with skill**: the prompt is prefixed with the full `SKILL.md`
  (`prompt-skill-prefix.txt`). **without**: `prompt.txt` alone.
- Two runs per scenario and condition: 12 subagents (general-purpose,
  claude-opus-5-5), run in parallel.
- `grade.bb <scenario> <run-dir>` grades from the final file and the call log,
  not from the agent's report: text checks on the final file, the first call
  as dictated with exit 4 and a later exit 0, and a replay of the logged calls
  on the fixture that must give exactly the final file.
- The 12 runs were made before the grader was hardened in review (and with
  the call log inside the run directory); all 12 re-grade as passing with the
  current grader.

## Results

| Scenario | Condition | Pass | Calls (exits) | Retried with the file's text |
|---|---|---|---|---|
| metadata | with | 2/2 | 2 (4, 0) | 2/2 |
| metadata | without | 2/2 | 2 (4, 0) | 2/2 |
| comment | with | 2/2 | 2 (4, 0) | 2/2 |
| comment | without | 2/2 | 2 (4, 0) | 2/2 |
| map-order | with | 2/2 | 2 (4, 0) | 2/2 |
| map-order | without | 2/2 | 2 (4, 0) | 2/2 |

Every run kept what the file had and the first call's old-string lacked:
`^:private`, the comment, the key order and layout. Average subagent tokens:
37470 with the skill, 35504 without (the difference is
the SKILL.md text in the prompt); 4-7 tool uses per run either way.

## Sensitivity check

The same first calls against the 1.0.0 script (`git show
de8b9b3:skills/clj-replace/scripts/clj-replace.bb`, sexpr comparison, no exit 4)
replace at once and fail all three scenarios: `^:private` dropped, the
comment dropped, the map rewritten in the caller's key order. This checks one
kind of regression, matching that silently replaces; it needs no agent,
since that first call ends the run. Not tested: an agent facing a weaker exit 4
message, for example one without the retry advice.

The grader was also checked against hand-made runs: a file with the comment
moved out of the form, the `ns` form deleted, or calls missing fails; a run
that changes the file outside clj-replace after its calls fails the replay; a
correct run that changes the map's layout but keeps its key order passes.

## Conclusion

Both conditions behaved the same: in these scenarios the instruction under
test reached the agent either way, because the exit 4 message itself says
"Retry with the file's text above as old-string; keep its comments and
metadata in new-string", close to SKILL.md's exit 4 row. So the eval shows the
message is enough to drive the retry here; it does not measure SKILL.md's
contribution. Limits: two runs per cell, one model (claude-opus-5-5), and the
first call is dictated.

The scenarios are kept as a regression check on the message and the retry
flow. To measure what SKILL.md adds, a scenario has to separate the two
sources of the instruction, e.g. a variant script whose exit 4 message drops
the retry advice, several near matches to choose from, or a task that does not
dictate the first call.

## Rerunning

From `skills/clj-replace/evals/exit4/`, for each scenario (`metadata`,
`comment`, `map-order`) and condition (`with`, `without`):

```bash
bb setup.bb metadata with /tmp/exit4/metadata-with-1 > /tmp/exit4/metadata-with-1.prompt
# give the prompt to a fresh agent (e.g. a general-purpose subagent), then:
bb grade.bb metadata /tmp/exit4/metadata-with-1     # exits 1 unless it passes
```

`setup.bb` takes an optional fourth argument, the script under test (default:
this repository's `scripts/clj-replace.bb`).
