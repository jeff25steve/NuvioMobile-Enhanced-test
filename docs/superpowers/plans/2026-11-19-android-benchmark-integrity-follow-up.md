# Android startup benchmark integrity follow-up — 19 November 2026

**Branch:** `main-build-testing`  
**Starting commit:** `f77e544816872359bf26b64069cd5501ae6bebb2`  
**Goal:** Make the phone-only startup result safer to interpret using evidence already captured by the runner. Preserve the existing three compilation arms, ten cold iterations, Perfetto outputs, benchmark package isolation and manual-only execution.

## Constraints

- Shell-only benchmark changes; do not alter app runtime, AndroidX APIs, dependencies, APK variants, workflow triggers or production package.
- Do not run Gradle, build, CI, instrumentation or a device benchmark.
- Keep missing/vendor-specific thermal diagnostics best-effort and visible as unknown, never silently treat missing data as safe.
- Treat endpoint snapshots as endpoint snapshots; they cannot prove that no transient thermal event occurred between them.

## Cycle 1 — thermal status integrity

- [x] Add regression fixtures for a moderate-or-higher thermal status before measurement, a status rising after measurement, a light-only status, and unavailable thermal diagnostics.
- [x] Parse the exact `Thermal Status` field from the existing environment snapshot, not by issuing additional dumpsys calls.
- [x] Refuse to start a new measurement when the pre-run status is moderate-or-higher (2–6); allow light status (1) and record unknown as unverified.
- [x] Record pre/post status and an explicit verdict in `thermal-integrity.txt`; include it in `benchmark-summary.txt` and the copied-files manifest.
- [x] Mark a run thermally contaminated when the post-run snapshot is 2–6. Preserve the raw data, but warn against comparing it as an unthrottled result.

## Cycle 2 — actual comparison-arm execution order

- [x] Add a regression fixture that emits the three JUnit test status records in reverse order.
- [x] Capture the first-observed order of the three named test cases from AndroidJUnitRunner's actual instrumentation status log.
- [x] Append the observed sequence to the human-readable benchmark summary; reject an incomplete or unexpected set rather than presenting a guessed order.

## Review and verification boundary

- [x] Re-fetch the changed files and inspect the complete remote diff and branch ancestry.
- [x] Update the existing benchmark/engineering notes with rationale, source evidence, scope, and verification limits.
- [x] Do not claim the full repository shell regression suite passed: it was not executed because no local checkout was available. Only isolated POSIX shell/AWK helper fixtures were run. No Gradle task, build, CI, instrumentation or real-device measurement was started.
- [x] Verify no workflow file or production app source changed, and verify the final `main-build-testing` head and changed paths.


## Completed result

- The runner parses the exact numeric thermal status from the already-captured before/after environment snapshots. It blocks statuses 2–6 before instrumentation, permits 0–1, treats unavailable data as unknown, and flags post-run 2–6 as thermally contaminated.
- The summary records the first-seen order of all three startup test cases from AndroidJUnitRunner status records.
- Regression fixtures cover pre-run moderate rejection, light status, post-run moderate contamination, unavailable diagnostics and reversed JUnit case order.
- Static source-contract checks protect the runner behavior. A targeted local POSIX shell/AWK fragment check passed after an AWK reserved-word issue was found and fixed.
- The full repository shell regression scripts were not executed because no local checkout was available in this session. No Gradle task, build, instrumentation, physical-device benchmark or CI run was started.
- Final remote scope review confirmed that the change set contains only the phone benchmark runner, its two regression scripts, this plan and the two engineering notes. No workflow, production app source, Android benchmark class, dependency or build configuration changed.
