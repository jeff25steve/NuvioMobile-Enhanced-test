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


## Additional review cycles — 19 November 2026

### Cycle 3 — align readiness diagnostic assertions

- [x] Replace the stale `HomeScreen marker visible now:` and `Fully-drawn marker visible now:` expectations in `scripts/tests/test-nuvio-phone-benchmark.sh` with the current active-window diagnostic labels emitted by `StartupReadiness.java`.
- [x] Reproduce the mismatch with an isolated POSIX shell fixture before editing: the old assertion exited 1 against the current diagnostic text.
- [x] Re-fetch the updated test from the remote branch and verify both new labels are present and both stale labels are absent.
- Scope: regression-test repair only; no product readiness behavior changed.

### Cycle 4 — guard the complete Activity fully-drawn callback chain

- [x] Extend `scripts/tests/test-android-startup-readiness.sh` to assert that `MainActivity` passes `appReady` into `ReportDrawnWhen`.
- [x] Assert that `MainAppContent` derives root readiness from real Home readiness and profile-switch completion, then forwards the overlay-gated result.
- [x] Assert that the normal `AppGate` bridge forwards the ready state to `onAppReady` only while the Main gate is active and profile selection is complete.
- [x] Exercise the new bridge assertions with a deliberately broken local fixture (expected exit 1) and a valid bridge fixture (exit 0).
- Scope: additional regression coverage; no product readiness behavior changed.

### Evidence-led non-changes

- The inspected normal Android path is connected end-to-end: Home's initial-content callback updates `initialHomeReady`; `MainAppContent` combines it with profile-switch state and overlay exit; `AppGate` forwards the result; `MainActivity` exposes the same state to `ReportDrawnWhen` and the `nuvio_startup_ready` marker. No static callback break was found. A device trace is still required to reproduce the Grok-reported mismatch before changing runtime readiness semantics.
- The 78 `.initialize(...)` call count is not a cost measurement. The inspected P2P engine and subtitle cache initializers only store the application context. Sentry initialization does perform SDK startup synchronously, but it was not moved: no trace establishes its cost, and deferring crash reporting would change early-startup diagnostics.
- The large Baseline Profile file is not empty. The GitHub file-content retrieval returned an empty body for that large file, but an independent read of the branch's raw file showed non-empty profile rules. The inclusive `rule -> true` filter was not changed because no verified rule-coverage defect was established.
- UiAutomator's `UiObject2.scroll()` return value indicates whether the object can still scroll in the requested direction after the gesture. A false result can mean a successful scroll reached the boundary, so it is not a reliable no-movement test; `HomeBrowseReadiness.scrollRequired()` was left unchanged.

### Verification boundary

- Remote commits: `06922df65280346d697635f3e31a95f6c3aba688` (diagnostic assertion repair), `e3d36e382bab72f44550a58c361c6dac6fbec60f` (full readiness-chain guard), and `bfe82cedb2908cf879f740dda69be4123867cef6` (shell newline-format correction in the guard).
- The focused shell fixtures were exercised locally. The full repository shell regression scripts were not run because a complete local checkout was unavailable.
- No Gradle task, build, CI, instrumentation, physical-device benchmark, or device trace was started.
- Runtime sources, dependencies, generated profiles, workflow files, and benchmark measurement settings were not changed.


### Baseline Profile artifact metadata clarification

- [x] Verify the GitHub Contents API metadata for `androidApp/src/main/baseline-prof.txt`: blob `100a8b7d1fb814292afac304ace5c8d98ec02a54`, checked-in text size `1,409,345` bytes.
- [x] Independently inspect the branch's raw file and confirm it contains non-empty class/method rules; the empty `fetch_file` body was a large-file retrieval limitation, not an empty profile.
- The 1,409,345-byte value is the source text size only. It does not establish the packaged `assets/dexopt/baseline.prof` size, the profile installation result, ART compilation status, or an actual runtime performance gain. Those require artifact inspection and device-side `ProfileVerifier`/Macrobenchmark evidence.
