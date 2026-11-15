# Android startup benchmark evidence quality implementation plan

> **For agentic workers:** Execute inline with the Superpowers TDD and verification workflow. Tasks use checkbox syntax.

**Goal:** Make the phone-only cold-start benchmark reject incomplete iteration statistics and preserve device conditions needed to interpret noisy results.

**Architecture:** Keep all checks in the existing LADB shell runner and its fixture-based regression test; do not add packages or alter app runtime code. Validate AndroidX Macrobenchmark's per-test metadata and per-metric sample arrays, then save raw battery, thermal-service and display-brightness snapshots around the measurement.

**Tech Stack:** POSIX shell, Android shell commands (`dumpsys`, `settings`), POSIX `awk`; AndroidX Benchmark 1.5.0 JSON output.

**Spec:** User request to continue `main-build-testing`, prioritise benchmark improvements, use Context7/Exa/GitHub, update notes, and do not run builds or CI.

## Global Constraints

- Keep the production package `com.nuvio.media` untouched.
- Keep the phone runner compatible with Android LADB shell; do not require Python, jq, root, or a computer.
- Keep the benchmark manual-only; do not start builds, CI, APK assembly, or device tests.
- Do not change the startup journey, readiness markers, app behavior, dependencies, or baseline-profile rules.
- Preserve full and Play Store variants and the ten-iteration cold-start comparison.

## Review Focus

- Missing or non-numeric samples in one TTID/TTFD metric.
- `repeatIterations` not matching the ten requested measurements.
- Invalid metric statistics where minimum, median, and maximum are absent or out of order.
- JSON whitespace/newlines and decimal/exponent sample values.
- Missing or unavailable Android diagnostic commands must not replace the original instrumentation result or cause a false benchmark failure.

---

### Task 1: Validate actual Macrobenchmark iteration statistics

**Files:**
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Contract: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- Consumes the existing `*benchmarkData.json` file emitted by AndroidX Macrobenchmark.
- Produces a fail-closed result check requiring two named result records, two `repeatIterations=10` records, both TTID/TTFD metric objects for both records, ten numeric `runs` samples per metric, and sane positive min/median/max statistics.

- [x] Add negative fixture cases for short sample arrays, wrong repeat count, and invalid min/median/max ordering; confirm they fail before changing the runner.
- [x] Add the validator to the phone runner using the existing Android `awk` dependency only.
- [x] Require exactly ten numeric samples in each of the four startup metric result arrays.
- [x] Require positive numeric min/median/max with `minimum <= median <= maximum`.
- [x] Re-run the isolated shell regression and shell syntax checks.

### Task 2: Capture device conditions around measurements

**Files:**
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-nuvio-phone-benchmark.sh`

**Interfaces:**
- Writes `environment-before.txt` and `environment-after.txt` in each Macrobenchmark run directory.
- Captures timestamp, battery power/level/temperature, thermal-service status, battery-saver state and display brightness/mode.
- Snapshot collection is best-effort: missing diagnostic commands or non-zero dumpsys responses are recorded as unavailable and never mask the benchmark result.

- [x] Extend the Android shell stubs and assert both snapshots are written for each flavor.
- [x] Add snapshot capture immediately before and after Macrobenchmark instrumentation.
- [x] Keep the raw result logs and JSON files unchanged.
- [x] Re-run the isolated shell regression and shell syntax checks.

### Task 3: Update engineering notes and review

**Files:**
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`

- [x] Record both improvement cycles, source basis, verification results and limitations.
- [x] Review the remote diff for scope, shell portability, and workflow/manual-only status.
- [x] Verify the final remote branch head and changed paths.

## Execution record — 15 November 2026

- Implemented on `main-build-testing` directly through sequential commits; no force update or merge was used.
- The exact validator logic was exercised locally: realistic and whitespace-formatted two-arm JSON passed, while wrong repeat counts, truncated arrays, missing samples, non-numeric samples, and invalid statistics failed.
- The device-condition snapshot helper was exercised with successful and failing diagnostic command fixtures; it preserved available readings and remained non-fatal when diagnostics failed.
- The full repository regression script was not run end-to-end. No build, CI, APK assembly, or device benchmark was run.
- Performance conclusions are deferred until a new authorized real-device run.

