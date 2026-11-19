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

- [x] Add static contract assertions for the split version references and stable plugin pin.
- [x] Add static contract assertions that the original `StartupBenchmark` remains ten iterations and does not enable heap profiling.
- [x] Add the new Java class with the documented 1.6.0-alpha02 experimental API and shared readiness flow.
- [x] Re-fetch the catalog, both benchmark classes and static contract; verify only the macrobenchmark library moved to alpha.

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

- [x] Add fake-device fixture generation for a valid isolated memory-profile result and trace.
- [x] Add negative fake fixtures for missing output, duplicate output, wrong profiler type, wrong suffix, missing file and empty file; wire each into the executed invalid-fixture loop.
- [x] Add the new `memory-profile` action, staging rules, API 33 gate, filtered instrumentation call and dedicated output validation.
- [x] Source-review the normal `measure` and `run` paths: they still call the original three-case validator and its exact ten-iterations-per-arm / 30-trace contract.
- [x] Re-fetch all shell sources and inspect action routing, trace validation and fixture wiring.

### Task 3: Engineering record and remote review

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Modify: this plan and its design spec.

- [x] Record the AndroidX release/source findings, API version split, separate profiling-pass behavior and explicit no-speedup boundary.
- [x] Record the Android Performance Analyzer / Perfetto viewing workflow and pprof conversion reference, plus heapprofd sampling semantics and profileable prerequisites.
- [x] Re-fetch every changed file and compare the branch against `11d334fda5ec4d5d5928b893d779c1533130caf9`.
- [x] Confirm only the benchmark test module, version catalog, phone runner, regression/static-contract scripts and benchmark docs changed.
- [x] Confirm no app source or workflow changed, and no build/CI/instrumentation/LADB/device run was started.

## Execution record

The implementation will be integrated natively in this session because the request explicitly asks to continue implementation. No local repository checkout is available through the GitHub file integration, so full shell-suite execution and Java/Kotlin compilation cannot be claimed. The phone regression scripts will be extended and source-reviewed; the new APK/class becomes runnable only after a separately authorized build creates an updated benchmark test APK.


## Verification tasks deliberately left pending

- [ ] Run `sh scripts/tests/test-nuvio-phone-benchmark.sh` from a local checkout.
- [ ] Run `sh scripts/tests/test-android-startup-metric-contract.sh` from a local checkout.
- [ ] Compile the Baseline Profile test module and inspect the merged Full/Play Store BenchmarkRelease manifests after an explicitly authorized build.

## Execution record — 19 November 2026

- Added the version-catalog split, isolated Java benchmark class, separate phone action, dedicated summary and strict trace validation. Regression fixtures cover valid output and the eight invalid cases.
- Added static protection for the app's existing shell-profileable declaration and the Play Store manifest overlay not removing it.
- Ran isolated POSIX AWK/shell checks against the same memory-output extraction/filename logic and nine fixture modes (valid + eight invalid). Focused checks passed. These are not the repository test scripts.
- Re-fetched the new runner, fixture script, static contract, version catalog, Java class and engineering notes. The normal timing path remains separate by source inspection.
- **Not verified:** the complete repository phone-runner and static-contract scripts were not run; Java was not compiled; the merged APK manifest was not built/inspected. The GitHub integration cannot execute source files and no local checkout is available in this session (the attempted clone could not resolve github.com).
- **Final review:** self-review (no subagent tool available). Findings and residual limitations are recorded here; a fresh reviewer and full test run remain outstanding.
- No Gradle task, build, CI, instrumentation, profile collection, LADB action or physical-device capture was started. The memory-profile action requires a newly built BenchmarkRelease test APK containing `StartupMemoryBenchmark`.


### Additional result-identity hardening

- [x] Require JSON `className` to match `StartupMemoryBenchmark` and require `repeatIterations=1`; the instrumentation log class/test status must also match.
- [x] Add fake invalid-result variants for a wrong JSON class name and an unexpected repeat-iteration count; include both in the memory-profile invalid-fixture loop.
- [x] Protect the shared app `profileable android:shell="true"` declaration and assert that neither Full nor Play Store overlays removes it.
- [x] Add the actual AndroidX heapprofd source settings (2,048-byte sampling interval, 500 ms continuous dumps, 8 MiB buffer) and sampling caveat to the generated memory summary.

- [x] Record `block_client=true` and Perfetto's earliest Zygote-specialization coverage limitation in the diagnostic summary and engineering notes.


## Final remote review — 19 November 2026

- Final branch head at review: `6789dd058aacbc208eda176bdedfad1b446661b5`.
- Compared with checkpoint `11d334fda5ec4d5d5928b893d779c1533130caf9`: 40 commits ahead, zero behind.
- GitHub compare shows exactly nine changed paths: the new instrumentation test class, the benchmark version catalog, phone runner, two regression/static-contract scripts, two existing engineering notes and the new plan/spec.
- No app manifest, production app source, Gradle build script, workflow or CI file changed. The app/profileability manifests were read-only inputs to a new static contract.
- Re-fetched all nine changed paths and the unmodified `StartupBenchmark.java` and app manifest inputs. Source assertions confirmed the normal ten-iteration benchmark is unchanged; only the macrobenchmark instrumentation library uses 1.6.0-alpha02 while the Baseline Profile plugin remains 1.5.0.
- Full shell scripts and Java compilation remain pending. No builds, CI, instrumentation, LADB or device actions were started.


## Follow-on engineering cycles — 19 November 2026

The heap-diagnostic work above remains unchanged. Two further benchmark-only improvement cycles were added after the initial remote review.

### Cycle 79 — deterministic bootstrap intervals

- [x] Research a practitioner benchmark using interleaved cold starts and bootstrap confidence intervals. Source: Bluesky PR #12092.
- [x] Add 10,000 fixed-seed, independent-arm bootstrap resamples for TTID and TTFD median differences (Baseline Profile vs partial/no profile).
- [x] Keep the original three JUnit arms, ten measured iterations per arm, compilation modes, readiness gates and trace contract unchanged.
- [x] State in the generated summary that the percentile intervals do not correct the existing grouped-arm ordering, thermal or time-drift confounders.
- [x] Extend fake-phone and static-contract assertions for the new interval output and limitation.
- [x] Run focused, isolated AWK checks for the fixed-seed bootstrap calculation.
- [ ] Run the repository phone-runner and metric-contract scripts from a local checkout.

**Design boundary:** the public experiment describes interleaving the three build/configuration runs, but does not provide a complete interleaving script in its checked-in Macrobenchmark class. Context7 confirms AndroidJUnitRunner supports tests_regex filtering by Class#method. A 30-method rotation was not added because each separate MacrobenchmarkRule invocation repeats compilation reset and the no-profile warmup, altering total device work and adding heat. A future interleaved harness must specify compilation/warmup state and validate each trace/result before being used as the main comparison.

### Cycle 80 — profile artifact inventory

- [x] Add a read-only profile audit after both the Startup Profile and Home-browse Baseline Profile pass their existing non-empty checks.
- [x] Record per-file SHA-256, nonblank lines, unique exact rules, exact duplicates, method/class-or-other candidates and H/S/P flag counts.
- [x] Keep original profile text unchanged; the audit does not normalize, deduplicate, sort, rewrite or reject AndroidX output.
- [x] Extend fake profile fixtures with a duplicate and flag-bearing rules, then assert that the audit reports them.
- [x] Add static-contract checks for audit presence and execution after profile validation.
- [x] Run focused isolated AWK checks against synthetic profile data.

### Evidence and deferred work

- Bluesky PR #12092 (opened 20 October, updated 4 November 2026) reports 45 interleaved cold starts per configuration on a Pixel 7a, bootstrap 95% intervals and a TTID drift as the phone heated. It is a useful single-team result, not independent consensus and not a Nuvio measurement.
- Searches did not identify a second independent, reproducible social/forum/GitHub method stronger than that PR and official AndroidX/Android documentation. AndroidX PowerMetric / ODPM rail profiling is hardware-dependent and not assumed available on arbitrary Android phones.
- AndroidX Benchmark 1.6.0-alpha02 release improvements (thermal cooldown handling, Perfetto output buffering, target process shutdown behavior and optional trace categories) are already present via the pinned Macrobenchmark test dependency. No extra workaround was added.
- The generated bootstrap intervals are exploratory. A rotated/interleaved same-device comparison is still needed to control grouped-arm order and thermal bias.
- No Nuvio startup-speed improvement, memory reduction, power reduction or outperformance versus official Nuvio is claimed.

### Verification and remote-state boundary

- The deterministic bootstrap and profile-inventory AWK logic were exercised in isolation with synthetic sample/profile fixtures. These were not executions of the repository's test scripts.
- GitHub source re-fetches confirmed the runner changes, test assertions and static contract are present.
- The complete scripts/tests/test-nuvio-phone-benchmark.sh and scripts/tests/test-android-startup-metric-contract.sh suites remain unrun because no local checkout is available through the GitHub integration.
- No Java/Kotlin compilation, Gradle build, CI, instrumentation, Baseline Profile collection, LADB command or physical-device benchmark was run.
- The branch head when this addendum was prepared was 504e907e1e99366ae86255b79bc119ccdc459f37; the final branch head is verified separately after this plan update.
