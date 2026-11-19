# Isolated Android Startup Heap Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional, standalone cold-start ART/native heap profile to the physical-phone benchmark without changing the clean startup timing comparison.

**Architecture:** Split the Benchmark library and Baseline Profile plugin versions so the Macrobenchmark test APK can use AndroidX Benchmark 1.6.0-alpha02 while the Gradle Baseline Profile plugin remains on stable 1.5.0. Add a dedicated `StartupMemoryBenchmark` JUnit class and a `memory-profile` phone-runner action that validates the separate `Memory Profiling Iteration 0` trace.

**Tech Stack:** Java AndroidX Macrobenchmark, AndroidX Benchmark 1.6.0-alpha02 experimental API, POSIX shell/AWK, Perfetto heap profile traces.

**Spec:** `docs/superpowers/specs/2026-11-19-isolated-startup-heap-diagnostics-design.md`

## Global Constraints

- Keep the existing `StartupBenchmark` three-arm comparison, ten iterations per arm, TTID/TTFD/ART metrics, clean traces and readiness journey unchanged.
- Use `androidx.benchmark:benchmark-macro-junit4:1.6.0-alpha02` only for the instrumentation test module.
- Keep the `androidx.baselineprofile` Gradle plugin on `1.5.0`.
- Keep the production app dependency graph and app source unchanged.
- Profile ART and native heaps only in AndroidX's dedicated additional profiling pass.
- Do not run Gradle, build, CI, instrumentation, LADB or physical-device operations.
- Do not claim a measured startup speedup or heap-allocation reduction.

## Review Focus

- The clean timing benchmark must not gain a `MemoryProfilingConfig` or additional profiler outputs.
- A memory diagnostic must use the packaged Baseline Profile and shared target-owned Home/fully-drawn readiness.
- Missing, duplicate, wrong-type, wrongly suffixed or empty memory traces must fail closed.
- The new action must not weaken existing API 33, thermal, Battery Saver, APK provenance or installation-variant gates.
- The new alpha library version must not change the Baseline Profile Gradle plugin version.

---

### Task 1: Isolate the new AndroidX experimental API and add the dedicated startup test

**Files:**
- Modify: `gradle/libs.versions.toml`
- Create: `baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupMemoryBenchmark.java`
- Test: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- New catalog version: `androidx-benchmark-macro = "1.6.0-alpha02"`.
- Keep `androidx-benchmark = "1.5.0"` and keep the `baselineProfile` plugin's `version.ref` set to `androidx-benchmark`.
- Change only the `androidx-benchmark-macro` library's `version.ref` to `androidx-benchmark-macro`.
- New test class: `com.nuvio.android.baselineprofile.StartupMemoryBenchmark`.
- New JUnit test method: `startupWithBaselineProfileMemoryProfile()`.
- Configure `ExperimentalConfig(null, new StartupInsightsConfig(true), new MemoryProfilingConfig(true, true))`.
- Use `CompilationMode.Partial(BaselineProfileMode.Require)`, `StartupMode.COLD`, one normal measured iteration, `StartupTimingMetric` + `ArtMetric`, and the existing `StartupReadiness` helper.

- [ ] Add static contract assertions for the split version references and stable plugin pin.
- [ ] Add static contract assertions that the original `StartupBenchmark` remains ten iterations and does not enable heap profiling.
- [ ] Add the new Java class with the documented 1.6.0-alpha02 experimental API and shared readiness flow.
- [ ] Re-fetch the catalog, both benchmark classes and static contract; verify only the macrobenchmark library moved to alpha.

### Task 2: Add the isolated phone action and strict memory-trace validation

**Files:**
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- New shell constant: `MEMORY_BENCHMARK_CLASS=com.nuvio.android.baselineprofile.StartupMemoryBenchmark`.
- New phone action: `memory-profile full|playstore`.
- Run it through `androidx.benchmark.enabledRules=Macrobenchmark`, filtering only the new memory test class.
- New validator: `require_memory_profile_result_data()`.
- The validator requires exactly one `benchmarkData.json`, exactly one `startupWithBaselineProfileMemoryProfile` result, exactly one direct `Memory Profiling Iteration 0` output object, type `PerfettoTrace`, a filename ending `-memoryProfiling.perfetto-trace`, and exactly one copied non-empty trace at that filename.
- Create a dedicated `memory-profile-summary.txt` that labels the output as an allocation diagnostic, lists ART/native sampling as enabled, records the trace filename and device/thermal/Battery Saver/provenance evidence, and provides Perfetto/APA opening guidance.
- The action reuses the existing install-variant/provenance and physical-device/thermal/Battery Saver checks. API 33 keeps the existing rooted instrumentation-shell requirement.

- [ ] Add fake-device fixture generation for a valid isolated memory-profile result and trace.
- [ ] Add negative fake fixtures for missing output, duplicate output, wrong profiler type, wrong suffix, missing file and empty file; wire each into the executed invalid-fixture loop.
- [ ] Add the new `memory-profile` action, staging rules, API 33 gate, filtered instrumentation call and dedicated output validation.
- [ ] Verify the normal `measure` and `run` paths still use the original three-case validator and exactly thirty timing traces.
- [ ] Re-fetch all shell sources and inspect action routing, trace validation and fixture wiring.

### Task 3: Engineering record and remote review

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Modify: this plan and its design spec.

- [ ] Record the AndroidX release/source findings, API version split, separate profiling-pass behavior and explicit no-speedup boundary.
- [ ] Record the Android Performance Analyzer / Perfetto viewing workflow and pprof conversion reference.
- [ ] Re-fetch every changed file and compare the branch against `11d334fda5ec4d5d5928b893d779c1533130caf9`.
- [ ] Confirm only the benchmark test module, version catalog, phone runner, regression/static-contract scripts and benchmark docs changed.
- [ ] Confirm no app source or workflow changed, and no build/CI/instrumentation/LADB/device run was started.

## Execution record

The implementation will be integrated natively in this session because the request explicitly asks to continue implementation. No local repository checkout is available through the GitHub file integration, so full shell-suite execution and Java/Kotlin compilation cannot be claimed. The phone regression scripts will be extended and source-reviewed; the new APK/class becomes runnable only after a separately authorized build creates an updated benchmark test APK.
