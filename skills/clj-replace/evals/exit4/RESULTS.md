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
  names the change, gives the first call's two strings, and forbids editing
  the file any other way or reading outside the directory.
- **with skill**: the prompt includes the full `SKILL.md`. **without**: only
  the command line.
- Two runs per scenario and condition: 12 subagents (general-purpose,
  claude-opus-5-5), run in parallel.
- `grade.bb <scenario> <run-dir>` grades from the final file and the call log,
  not from the agent's report.

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

The same first calls against the 1.0.0 script (sexpr comparison, no exit 4)
replace at once and fail all three scenarios: `^:private` dropped, the
comment dropped, the map rewritten in the caller's key order. So the eval
detects the problem exit 4 exists for.

## Conclusion

The exit 4 message is self-sufficient for this flow: agents retried correctly
from the message alone, so these scenarios do not separate the two
conditions (a ceiling effect). They are kept as a regression check on the
message and the retry flow: a weaker message, or matching that silently
replaces, would show up as failed checks. A harder scenario is needed to
measure what SKILL.md adds, e.g. several near matches to choose from, or a
task that does not dictate the first call.

## Rerunning

Set up one directory per scenario and condition as above (copy
`clj-replace-logged.bb` to `clj-replace.bb` and the script under test to
`real-clj-replace.bb`, write the scenario's `:file` to `target.clj`), give
each agent the task, then run `bb grade.bb <scenario> <run-dir>`.
