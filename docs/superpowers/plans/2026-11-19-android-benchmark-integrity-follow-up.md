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

- [ ] Add regression fixtures for a moderate-or-higher thermal status before measurement, a status rising after measurement, a light-only status, and unavailable thermal diagnostics.
- [ ] Parse the exact `Thermal Status` field from the existing environment snapshot, not by issuing additional dumpsys calls.
- [ ] Refuse to start a new measurement when the pre-run status is moderate-or-higher (2–6); allow light status (1) and record unknown as unverified.
- [ ] Record pre/post status and an explicit verdict in `thermal-integrity.txt`; include it in `benchmark-summary.txt` and the copied-files manifest.
- [ ] Mark a run thermally contaminated when the post-run snapshot is 2–6. Preserve the raw data, but warn against comparing it as an unthrottled result.

## Cycle 2 — actual comparison-arm execution order

- [ ] Add a regression fixture that emits the three JUnit test status records in reverse order.
- [ ] Capture the first-observed order of the three named test cases from AndroidJUnitRunner's actual instrumentation status log.
- [ ] Append the observed sequence to the human-readable benchmark summary; reject an incomplete or unexpected set rather than presenting a guessed order.

## Review and verification boundary

- [ ] Re-fetch the changed files and inspect the complete remote diff and branch ancestry.
- [ ] Update the existing benchmark/engineering notes with rationale, source evidence, scope, and verification limits.
- [ ] Do not claim the shell regression suite passed unless it was actually executed. This session has remote GitHub access but no repository checkout in the local container; no builds, tests, CI, instrumentation or real-device measurements are authorized.
- [ ] Verify no workflow file or production app source changed, and verify the final `main-build-testing` head and changed paths.
