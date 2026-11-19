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

- [ ] Add fixture installation behavior to the fake `pm install` command and verify the state file is written from the correct app/test pair.
- [ ] Add assertions that a later standalone `collect` and `measure` run records the saved source filenames and 64-character SHA-256 hashes in `provenance.txt`.
- [ ] Run the focused fixture check before production changes; expect failure because no install provenance record currently exists and the invocation-local variables are empty.
- [ ] Implement robust `file_sha256()` failure handling and persist the install provenance after both package installs succeed.
- [ ] Add a loader that checks the saved provenance variant and fields before any instrumentation command.
- [ ] Change `write_run_provenance()` to use the saved record rather than unset `APP_APK` / `TEST_APK` process variables.
- [ ] Run focused shell fixture checks and record their exact result.

### Task 2: Detect installed package drift safely

**Files:**
- Modify: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Modify: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- Add a helper that consumes package name plus saved source SHA-256, queries `pm path PACKAGE`, and returns installed path/hash/verdict fields for `provenance.txt`.
- Verdicts are `MATCH`, `MISMATCH`, or `UNVERIFIED`.

- [ ] Add regression fixtures for app-package mismatch, instrumentation-package mismatch, and an unreadable installed APK path.
- [ ] Run the focused fixture before implementation; expect stale package fixtures to be accepted by the current runner.
- [ ] Implement pre-instrumentation checks: no path or multiple APK paths fails as unexpected installed package state; a readable, different SHA fails; an unreadable path is explicitly unverified and does not falsely claim a match.
- [ ] Add app/test installed code paths, hashes and verdicts to `provenance.txt`.
- [ ] Add static source-contract checks for the state loader, `pm path` verification and mismatch-before-`am instrument` ordering.
- [ ] Run focused shell fixture checks and record the exact output.

### Task 3: Documentation and remote review

**Files:**
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: this plan.

- [ ] Record the defect, fix, device access limitation and verification boundary.
- [ ] Re-fetch all changed remote files and inspect the complete diff/branch ancestry.
- [ ] Verify no app source, Gradle/build file, dependency, workflow or production package file changed.
- [ ] Do not claim the full phone-runner regression or build passed unless it was actually executed.
