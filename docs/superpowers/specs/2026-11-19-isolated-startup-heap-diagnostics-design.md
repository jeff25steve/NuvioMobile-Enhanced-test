# Isolated Android startup heap diagnostics — design

**Date:** 19 November 2026  
**Branch:** `main-build-testing`  
**Starting checkpoint:** `11d334fda5ec4d5d5928b893d779c1533130caf9`

## Intent

Add an optional physical-device diagnostic that captures ART (Java/Kotlin) and native heap allocation samples during a Baseline-Profile-enabled cold Home launch, without profiling the existing clean startup timing iterations.

The current benchmark uses AndroidX Benchmark 1.5.0. AndroidX 1.6.0-alpha01 introduced `MemoryProfilingConfig`; 1.6.0-alpha02 still includes that API and adds Macrobenchmark/Perfetto reliability changes. The current three-arm timing harness must not enable memory profiling: it enforces exactly ten clean per-iteration Perfetto traces per comparison arm, and profiling overhead must not be mixed into those timings.

## Concrete source findings

1. AndroidX `Macrobenchmark.kt` maintains separate `measurementIterationResults` and `profilingIterationResults`. It completes normal metric iterations first, then invokes a dedicated one-iteration memory profiling phase with an empty metrics list when `ExperimentalConfig.memoryProfilingConfig` is present. The ART/native heap profile is therefore not collected inside the normal timing iterations.
2. AndroidX `MacrobenchmarkPhase.kt` labels this output `Memory Profiling Iteration 0`; the phase uses the `-memoryProfiling` trace suffix and routes its trace through `profilerOutputs`, separately from ordinary `Trace Iteration N` outputs.
3. Android's `MemoryProfilingConfig` API supports ART heap sampling and native heap sampling, requires API 29+, and produces a Perfetto trace with embedded pprof profile data.
4. The repository's phone runner only admits API 33+ devices, so the API floor exceeds the heap profiler's documented minimum. Existing Baseline Profile compilation mode and readiness helper can be reused unchanged for the diagnostic launch.
5. The version catalog currently shares one `androidx-benchmark=1.5.0` version reference between the macrobenchmark test library and the Baseline Profile Gradle plugin. The macrobenchmark instrumentation library can move to a separate 1.6.0-alpha02 version reference while the Baseline Profile Gradle plugin remains on 1.5.0.

## Approaches considered

### A. Enable heap profiling on the existing three-arm timing benchmark — rejected

This would add memory profiling trace outputs to the clean timing result JSON and violate the current exact ten-trace-per-arm contract. It would also make the timing suite more complex and expose the main performance comparison to an unnecessary pre-release diagnostic feature.

### B. Upgrade only the macrobenchmark instrumentation library and add an isolated diagnostic class/action — selected

Split the version-catalog references: use 1.6.0-alpha02 only for `androidx.benchmark:benchmark-macro-junit4`, while keeping the `androidx.baselineprofile` Gradle plugin at stable 1.5.0. Add a dedicated test class with one cold Baseline Profile launch, normal TTID/TTFD/ART timing on that single unprofiled measurement iteration, and ART+native heap capture in AndroidX's additional profiling pass. Add a `memory-profile` phone-runner action that exports and validates the dedicated output separately. The existing `StartupBenchmark` remains untouched.

Trade-off: this uses a pre-release benchmark library in the instrumentation-only module and needs a newly built matching benchmark APK before the action can run. It does not affect the production app dependency graph or the stable Baseline Profile plugin.

### C. Use Android Performance Analyzer / Perfetto manually without a new benchmark action — useful but incomplete

Android Performance Analyzer is an official open-beta tool that can inspect Perfetto traces, but a manual workflow alone does not produce an ART/native allocation trace. It is a useful viewer for the exported memory trace, not a substitute for adding the capture pass.

## Goals

- Add a separate opt-in memory-profile startup test and `memory-profile full|playstore` phone action.
- Profile both ART and native heaps during one additional cold launch using the packaged Baseline Profile.
- Keep the normal ten-iteration three-arm timing benchmark, its metrics, trace count, APK provenance, environment gates and summary unchanged.
- Require exactly one memory-profile result with the expected class and `repeatIterations=1`, exactly one labelled `Memory Profiling Iteration 0` Perfetto output with the documented filename suffix, and a copied non-empty trace file.
- Produce a dedicated summary that clearly identifies this as a diagnostic profile, not a timing comparison or performance win.
- Document opening the trace in Perfetto UI / Android Performance Analyzer and converting embedded pprof data with Perfetto's `traceconv`.

## Non-goals and constraints

- No production application code changes.
- No change to the existing `StartupBenchmark` test methods, iteration count, metrics or readiness behavior.
- No new runtime Android dependency; the pre-release dependency is isolated to the benchmark test module.
- Keep the Baseline Profile Gradle plugin version stable at 1.5.0.
- Do not change workflows, start Gradle, build, CI, instrumentation, LADB or device operations.
- No startup speedup, heap reduction or allocation regression claim without an actual authorized device capture and analysis.
- The diagnostic must fail closed when the expected memory-profile output is missing, duplicated, mislabeled or empty.

## Verification

- Add source-contract checks proving the clean `StartupBenchmark` remains free of heap profiling and the separate test has the new ExperimentalConfig.
- Add fake-phone result fixtures for a valid memory-profile output, missing memory output, duplicate memory output, wrong filename suffix, empty/missing trace artifact, wrong JSON class and an unexpected repeat-iteration count.
- Ensure the regular fake timing run still requires only the original three comparison results and thirty clean traces.
- Re-fetch all changed files and compare the branch against the starting checkpoint.
- Full shell tests and compilation remain unverified unless run from a local checkout; no build/CI is authorized in this task.

## Confirmed production-device eligibility and interpretation constraints

- AndroidX's pinned `PerfettoConfig.kt` configures heapprofd with `com.android.art` and/or `libc.malloc`, an 8 MiB shared buffer, `sampling_interval_bytes=2048`, `block_client=true`, and 500 ms continuous dumps. The diagnostic summary must report these exact values, not generic Perfetto defaults.
- Perfetto documents that a target app on a normal Android user build must be `profileable` or `debuggable` for heapprofd. Nuvio's shared app manifest already declares `<profileable android:shell="true" />`; its Play Store overlay currently does not remove that declaration. A static regression assertion protects the source manifest and overlay. Since this task does not build variants, the merged manifest remains to be confirmed after an authorized build.
- The result is sampled allocation-call-stack evidence. It is not an exact allocation ledger, total Java/native heap, RSS/PSS, or a Java object-retention graph. AndroidX's own sampling config prioritizes allocation completeness/determinism over frame timing, and the profile is captured in a separate pass. No exact memory-footprint or startup regression claims may be derived from the single profile.


## Additional validation assertions

The memory result is accepted only when its single BenchmarkData result has JSON `className=com.nuvio.android.baselineprofile.StartupMemoryBenchmark` and `repeatIterations=1`, in addition to the matching AndroidJUnitRunner status records and profiler output contract. Regression fixtures cover a wrong JSON class and an unexpected repeat-iteration count.

The summary and review must also disclose that AndroidX's heapprofd config sets `block_client=true` (prioritizing allocation completeness/determinism over frame timing) and that process-name startup profiling may miss some allocations during the earliest Zygote specialization. This profiling pass is not a complete record of every process-start allocation.
