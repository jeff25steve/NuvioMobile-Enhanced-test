# Phone benchmark APK provenance implementation plan

> **For agentic workers:** Use inline execution with test-first steps. This plan is executed directly through the authorised GitHub integration; do not start a build, instrumentation, device benchmark or CI.

**Goal:** Make each phone benchmark result record the installed source APK pair accurately across LADB invocations and detect installed APK drift before instrumentation when Android makes the code path readable.

**Architecture:** Persist app/test APK filenames and SHA-256 values beside the existing variant marker during installation. Each later collection/measurement loads that record, queries `pm path`, compares installed bytes when readable, and writes source plus installed identity into the run provenance. A known mismatch fails; an inaccessible code path is clearly marked unverified.

**Tech Stack:** POSIX shell, Android `pm path`, `sha256sum` / Toybox `sha256sum`, existing fake-device shell fixtures. No Kotlin/Java/API/dependency change.

**Spec:** `docs/superpowers/specs/2026-11-19-phone-benchmark-installed-apk-provenance-design.md`

## Global Constraints

- Modify only `scripts/nuvio-phone-benchmark.sh`, its regression/static-contract scripts, and benchmark engineering notes.
- Preserve existing variant marker, test package isolation, benchmark cases, metrics, readiness, thermal checks, trace capture and summary semantics.
- Never uninstall, clear data from, or modify production `com.nuvio.media`.
- If installed APK file bytes are unreadable, record unverified; do not mislabel the package as matching.
- No Gradle task, build, CI, instrumentation or physical-device benchmark.

## Review Focus

- Separate LADB invocations must not lose source APK hashes.
- The combined `run` profile collection occurs before the benchmark APK is installed; it must use the saved NonMinifiedRelease identity.
- A stale/replaced app APK must be detected before launching instrumentation.
- A stale/replaced self-instrumenting test APK must be detected before launching instrumentation.
- Missing/unsupported `pm path`, split APK output, or shell read denial must not be mistaken for a byte match.

---

### Task 1: Persist install-time APK provenance

**Files:**
- Modify: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Modify: `scripts/nuvio-phone-benchmark.sh`

**Interfaces:**
- Install state file: `$OUTPUT_ROOT/current-install-provenance.txt`.
- Fields: `Variant`, `App APK filename`, `App APK SHA-256`, `Test APK filename`, `Test APK SHA-256`.
- The saved variant must equal the existing `ACTION:FLAVOR` marker.

- [x] Add fixture installation behavior to the fake `pm install` command and verify the state file is written from the correct app/test pair.
- [x] Add assertions that a later standalone `collect` and `measure` run records the saved source filenames and 64-character SHA-256 hashes in `provenance.txt`.
- [ ] Run the focused fixture check before production changes; expect failure because no install provenance record currently exists and the invocation-local variables are empty.
- [x] Implement robust `file_sha256()` failure handling and persist the install provenance after both package installs succeed.
- [x] Add a loader that checks the saved provenance variant and fields before any instrumentation command.
- [x] Change `write_run_provenance()` to use the saved record rather than unset `APP_APK` / `TEST_APK` process variables.
- [ ] Run focused shell fixture checks and record their exact result.

### Task 2: Detect installed package drift safely

**Files:**
- Modify: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Modify: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- Add a helper that consumes package name plus saved source SHA-256, queries `pm path PACKAGE`, and returns installed path/hash/verdict fields for `provenance.txt`.
- Verdicts are `MATCH`, `MISMATCH`, or `UNVERIFIED`.

- [x] Add regression fixtures for app-package mismatch, instrumentation-package mismatch, and an unreadable installed APK path.
- [ ] Run the focused fixture before implementation; expect stale package fixtures to be accepted by the current runner.
- [x] Implement pre-instrumentation checks: no path or multiple APK paths fails as unexpected installed package state; a readable, different SHA fails; an unreadable path is explicitly unverified and does not falsely claim a match.
- [x] Add app/test installed code paths, hashes and verdicts to `provenance.txt`.
- [x] Add static source-contract checks for the state loader, `pm path` verification and mismatch-before-`am instrument` ordering.
- [ ] Run focused shell fixture checks and record the exact output.

### Task 3: Documentation and remote review

**Files:**
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: this plan.

- [x] Record the defect, fix, device access limitation and verification boundary.
- [x] Re-fetch all changed remote files and inspect the complete diff/branch ancestry.
- [x] Verify no app source, Gradle/build file, dependency, workflow or production package file changed.
- [x] Do not claim the full phone-runner regression or build passed unless it was actually executed.


## Execution record — 19 November 2026

**Implementation status:** source, fixtures and documentation are committed. **Full repository test verification remains open**; the unchecked steps above are intentionally not marked complete.

- Implemented all three tasks on `main-build-testing`: persisted APK source provenance, best-effort installed APK byte verification with fail-closed mismatch handling, regression/static-contract fixtures, and engineering notes.
- The test fixtures were added before the runner implementation. The full repository test scripts could not be run because this session has no local working checkout and the connected GitHub integration provides source reads/writes rather than shell execution.
- A standalone POSIX shell helper fixture was run locally and passed exact-byte match, mismatch rejection, unreadable-path fallback, missing-path rejection and multi-path rejection. This does not establish that the complete phone runner or fixture script passes.
- Remote code review was performed by re-reading the complete changed runner functions and fixture insertion points. A fresh independent reviewer subagent is not available in this harness; this is an author self-review, not an independent review.
- No Gradle build, CI, Android instrumentation, profile generation, ADB/LADB action or device benchmark was run.
- AOSP source confirms `pm path` prints the base APK path and all split APK paths. Shell read access remains device-dependent, so `UNVERIFIED` is intentionally retained.


## Cycle 89 — 22 November device evidence: foreground ownership, stale nodes and profile flush

### Evidence separation

The uploaded captures are not one continuous run and must not be combined:

- The Play Store \`current-install.txt\` and \`current-install-provenance.txt\` identify \`install-profile:playstore\`. Its app/test source hashes were \`b75c9d09…a4d7bc8\` and \`c7c485c3…f32eb77\`.
- The separate Full-flavour \`provenance.txt\` identifies \`Action: run\`. It records the Full app hash \`25879b92…3d9e4e\` and the same test APK hash \`c7c485c3…f32eb77\`; both installed APK hashes matched their recorded source hashes. Its runner source and staged SHA-256 also matched (\`d585e7d0…2cede2e\`).
- The Play Store foreground snapshots show Nuvio's launcher activity before the run and Samsung Launcher as the top-resumed activity in the later after snapshot. They do not prove what held focus throughout the instrumentation interval.
- The separately uploaded 364-byte \`instrumentation.log\` ends immediately after the \`startup\` test begins (status code 1). It has no terminal test result and cannot establish a pass or fail.
- The video contains additional, older instrumentation output. One segment reports that \`com.draco.ladb\` owned the active window during readiness; another shows a \`StaleObjectException\` while HomeBrowse queried a \`UiObject2\`; a further log reports the ART profile flush check failed. These are separate observations, not a single same-run chronology.

### Changes made on this branch

- Added bounded, stale-only retries around the **complete read operation** for target marker visibility bounds and scrollability. Each retry re-runs the active-window/package-scoped lookup; it does not reuse the stale \`UiObject2\`.
- Kept \`scrollToElement\` and explicit scroll gestures outside the retry wrapper. A gesture may already have taken effect when a stale exception is raised; replaying it would alter the collection journey.
- Changed both profile-generation journeys to pass \`scope.getIteration()\` into readiness timing, so collection observations are not all mislabeled as iteration \`0\`.
- Corrected the stale-node host regression fixture to use UI Automator's public no-argument \`StaleObjectException()\` constructor. It now checks reacquisition after a stale property read, retry exhaustion and that unrelated runtime failures are not retried.
- Updated the phone runner to print the foreground warning before instrumentation. The generated phone bundle instructions now explicitly say to background LADB immediately after submitting the run command, and not return to LADB or interact with the phone until instrumentation completes.
- Added a best-effort post-failure diagnostic file containing the target-package process listing, ProfileInstaller receiver presence in \`dumpsys package\`, and filtered \`ProfileInstaller\` / \`AndroidRuntime\` logcat entries. The file is labelled as a post-run snapshot; it cannot prove process state at the exact flush instant.
- Quoted the logcat tag filters to prevent the shell from interpreting \`*\` as pathname wildcards.

### Profile-flush diagnosis: deliberately not guessed

The older \`never flushed profiles in any process\` failure remains unresolved. The failing stack is inside AndroidX \`CompilationMode.Partial.compileImpl\` after a warm-up journey; AndroidX checks whether ART profile data was successfully flushed by its own \`scope.killProcess()\` lifecycle. Do not add a second/manual process kill to the journey or change the warm-up count just to suppress the assertion.

The app currently declares no \`android:process\` components in the inspected main, Compose Android, Full or Play Store manifests. The app dependency is ProfileInstaller 1.4.1, and the main process uses the standard \`SAVE_PROFILE\` receiver path. The 1.5.0-alpha01 requirement found in current AndroidX mainline source applies to the newer PID-targeted save operation for secondary processes; the available evidence does not justify forcing that unreleased dependency onto this apparently single-process app. Keep 1.4.1 until the new failure diagnostics show a concrete receiver/version issue or a manifest/process change requires the newer API.

The diagnostic output should be used to distinguish: (1) no target process was running at AndroidX flush time, (2) ProfileInstallReceiver was missing or not merged into the installed APK, (3) the receiver rejected/failed the save broadcast, or (4) another process/environmental interruption occurred. The post-failure snapshot may narrow the investigation but a successful new on-device collection is still required to close the defect.

### Verification boundary

- Remote source and test wiring were re-fetched from this branch after the edits.
- An isolated Java fixture for the retry helper passed: \`PASS: stale-node retry behavior\`. The fixture compiled the helper against a minimal no-argument exception stub matching the documented public constructor; it is not an Android build or the full committed shell test.
- The complete repository shell regression scripts, Android compilation, instrumented tests, Baseline Profile collection and physical-device retest were not run in this session.
- No APK was rebuilt, no CI was started, and no change was merged to \`main-build-testing\`.
- The existing APK provenance matches remain valid for the captures that recorded them. They do not demonstrate that profile collection completed.
