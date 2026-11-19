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


## Priority review — P0 through P3 (19 November 2026)

### P0 — startup readiness contract

- [x] Re-trace the normal Android launch path from Home's `onInitialHomeContentRendered`, through `MainAppContent.rootContentReady` and overlay exit, across the normal `AppGate` bridge, to `MainActivity.appReady` and `ReportDrawnWhen`.
- [x] Keep the regression assertions that pin the callback hand-off and distinguish the independent Home marker from the Activity fully-drawn marker.
- The latest source still contains a connected callback chain. No static lost-callback defect was found, so no speculative readiness timeout or early-ready shortcut was introduced.
- **Not fully closed:** the originally reported device state still needs a real-device reproduction and trace. Source inspection cannot prove that the UIAutomator/active-window observation and Compose callback agreed at runtime. No device benchmark or instrumentation was started.

### P1 — reduce work before the first frame

- [x] Recount the Android `MainActivity.onCreate` calls: 78 `.initialize(...)` calls are present.
- [x] Inspect representative Android storage/platform initializers, including authentication/settings storage, profile stores, P2P, subtitle cache, downloads, MDBList, AniList, WeTrakr and PublicMetaDB.
- [x] Implement one source-proven deferral in `DownloadsLiveStatusPlatform`: `initialize()` now only retains the application context. The download notification channel is created lazily from `buildNotification()`, immediately before the notification builder is constructed.
- [x] Guard channel initialization with a volatile ready flag and a synchronized lock, preserving the existing-channel lookup and creation behavior. This ensures concurrent notification paths do not repeat the channel setup.
- [x] Add `scripts/tests/test-android-startup-initialization.sh` and invoke it from the startup readiness regression script. The guard checks that app initialization has no channel setup, that the channel is ensured before notification construction, and that setup is synchronized/one-time.
- The removed cold-start work was a NotificationManager channel lookup and, when absent, two localized resource reads performed through `runBlocking`. No elapsed-millisecond saving is claimed because no startup trace was run.
- The wider 78-call audit is not a license to move all remaining initializers. Many inspected initializers only retain an application context or a SharedPreferences handle; credential decryption and sync payload reads occur later. Theme locale application, launcher component recovery, Sentry startup and other behavior-sensitive setup were left in place without timing evidence.

### P2 — Baseline Profile quality and validation

- [x] Read the full checked-in `androidApp/src/main/baseline-prof.txt` blob (not just its GitHub Contents metadata).
- [x] Audit the profile text: 1,409,345 bytes; 11,375 non-empty unique rules; 1,012 class-only entries; 10,363 method entries; 2,087 method rules carrying the Startup flag; zero duplicate rules; zero malformed rules.
- [x] Confirm the generator contract: launcher startup uses `includeInStartupProfile=true`; Home browse uses `includeInStartupProfile=false`; both retain 15 max iterations, 3 stable iterations, strict stability and the unfiltered `rule -> true` predicate.
- [x] Add `scripts/tests/test-android-baseline-profile.sh` and wire it into the phone benchmark test. It checks the checked-in profile's text integrity, startup-vs-browse separation and the three startup comparison arms.
- No arbitrary profile filter or rule removal was made. The text-source size is not the packaged binary size. APK `assets/dexopt/baseline.prof`, ProfileVerifier installation/compilation status, Startup Profile DEX placement and ART use still require an authorized build/device validation; none is claimed here.

### P3 — benchmark harness and interpretation

- [x] Correct the phone runner's post-measurement guidance. It now explicitly names all three comparison arms: no compilation, profile-disabled partial compilation with three warmups, and profile-required partial compilation.
- [x] Clarify that measurement uses the profile already packaged in the benchmarkRelease APK; text generated by the separate collection step is not installed into that APK automatically.
- [x] Add regression assertions for this user-facing guidance.
- Existing thermal checks, active-window checks, per-iteration metric validation, ART metrics and bootstrap caveats were retained. Ten iterations and grouped JUnit arms remain exploratory; the runner already states that bootstrap intervals do not correct time/order/thermal drift.

### Focused verification and integration record

- [x] The P1 initialization guard was exercised against the eager original shape (expected failure) and the lazy/thread-safe shape (pass).
- [x] The P2 profile audit was exercised with a profile fixture, including a deliberately incorrect browse `includeInStartupProfile=true` configuration (expected failure), then with the correct false setting (pass). The full checked-in rule artifact was independently audited from its Git blob.
- [x] The P3 three-arm guidance assertion was exercised against the old two-arm text (expected failure) and the corrected guidance (pass).
- [x] Re-fetched the edited remote sources and confirmed that the new regression scripts are invoked from the existing benchmark test path.
- No full repository shell test suite was run from a complete checkout. No Gradle task, build, CI, instrumentation, physical-device benchmark, Perfetto capture, APK artifact inspection or ProfileVerifier run was started.
- The only product runtime change in these priority cycles is the lazy download notification channel setup. No measured startup-speed improvement is asserted.


### Cycle 81 — isolate same-second and overlapping benchmark outputs

- [x] Reproduce the current timestamp collision with a fixed clock: two invocations of the old `mkdir -p` allocation reuse the same action/flavor directory.
- [x] Add a focused shell regression that executes the real `new_run_dir()` function with a fixed clock and verifies distinct result paths and preserved markers.
- [x] Wire the focused regression into `scripts/tests/test-nuvio-phone-benchmark.sh`.
- [x] Replace `mkdir -p "$RUN_DIR"` with atomic directory reservation and deterministic numeric collision suffixes; retain the existing timestamp prefix and marker delay.
- [x] Run an isolated POSIX-shell check of the replacement with three fixed-time invocations and a 16-process overlap exercise (48 distinct allocations).
- [x] Re-fetch the remote runner, focused regression and parent test to verify the integrated source and paths.
- The complete checked-in focused test and parent shell suite were not executed because no local repository checkout is available to this GitHub integration. The isolated checks used a verbatim copy of the changed function; they do not replace full repository regression execution.
- No Gradle task, build, CI, instrumentation, LADB operation or device benchmark was run. No app startup speedup is claimed.


### Cycle 82 — cover concurrent allocation races

- [x] Extend the focused regression to launch 16 simultaneous shell subshells using the same fixed timestamp.
- [x] Require all 16 processes to report a distinct existing result directory with its own `.started` marker.
- [x] Execute the focused test logic in an isolated POSIX-shell harness with the updated function; it passed.
- The full parent phone-runner regression remains unrun because a local checkout is not available through the GitHub integration.


### Cycle 83 — instrument the P0 readiness observation stages

- [x] Re-trace the current Home → MainAppContent → AppGate → MainActivity callback chain; retain the existing product-ready gate because no source-proven callback defect was found.
- [x] Add monotonic timing for active-window ownership, Home marker, fully-drawn marker, and total UIAutomator readiness wait after startActivityAndWait().
- [x] Emit timings through AndroidJUnitRunner REPORT_KEY_STREAMRESULT, including stage-specific failure outcomes in instrumentation.log.
- [x] Give StartupBenchmark, BaselineProfileGenerator and StartupMemoryBenchmark stable journey names.
- [x] Keep Android Activity/Compose readiness state, ReportDrawnWhen, TTID/TTFD definitions, timeouts and production UI unchanged.

### Cycle 84 — count only measured iterations and reject incomplete timing evidence

- [x] Verify AndroidX Macrobenchmark runs the supplied measure block for unmeasured startup/compilation warm-ups and exposes a nullable public MacrobenchmarkScope.iteration value.
- [x] Pass the measured iteration index from StartupBenchmark and StartupMemoryBenchmark. Omit successful warm-up observations from the comparison summary; retain failed warm-up outcomes in the raw status log.
- [x] Require exactly ten readiness records for each of the three measured startup arms (30 total, iterations 0–9).
- [x] Reject missing, duplicate, malformed, unexpected, non-ready or internally inconsistent records.
- [x] Add fake instrumentation records and missing/malformed fixtures; extend startup-readiness, metric-contract and phone-runner source assertions.
- [x] Run isolated POSIX AWK parser fixtures: valid 30-record data accepted; missing, malformed, duplicate and unexpected-journey data rejected.
- The committed shell regression scripts were not run because a complete local checkout is unavailable. The isolated parser check is not a full suite pass. Java/Kotlin compilation is unverified.
- No Gradle task, build, CI, instrumentation, Baseline Profile collection or real-device benchmark was started. P0 runtime diagnosis remains pending an authorized device capture; no startup speedup is claimed.

 
### Cycle 85 — remove redundant PackageManager scans from the normal app-icon startup path

- [x] Trace `AppIconPlatform.initialize()` from `MainActivity.onCreate` and confirm it invokes `restoreDefaultIfNeeded()` before `super.onCreate`.
- [x] Confirm the main manifest sets `AppIconDefault` to `android:enabled="true"` and the enum contains six launcher aliases.
- [x] Verify from the official Android PackageManager reference that `COMPONENT_ENABLED_STATE_DEFAULT` means the component follows its manifest setting.
- [x] Check the default alias first and return unless it is explicitly disabled; scan alternative aliases only in that exceptional recovery case.
- [x] Preserve recovery when all aliases are disabled and preserve any explicitly enabled custom alias.
- [x] Add the shell regression guard and a Robolectric host regression for recovery and custom-alias preservation; wire the shell guard into the existing startup initialization test.
- [x] Run the shell guard against the previous recovery ordering (expected failure) and changed ordering (pass).
- The regular default-icon path now makes one `getComponentEnabledSetting` call instead of seven (six aliases plus a repeated default query). This is a source-derived PackageManager call-count reduction; no time saving is claimed.
- The Robolectric test and full committed shell suite were not run. No Gradle task, build, CI, instrumentation or device benchmark was authorized or started.
