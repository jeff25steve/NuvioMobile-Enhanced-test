# Android benchmark context provenance — design

**Date:** 19 November 2026  
**Branch:** `main-build-testing`  
**Starting commit:** `07e7d1fa8c8b979eb110f63b0acb08788f5aa3fe`

## Intent

Make the phone benchmark accept device eligibility metadata only from AndroidX BenchmarkData's documented root `context` object, and make the human-readable report use that same context instead of the first matching JSON key.

## Concrete defect

The runner comments claim to validate `context.build.version.sdk`, but the AWK code finds the first `build` key anywhere in the JSON and searches its `version.sdk`. A root-level decoy `build` object can therefore satisfy the API floor even when `context.build.version.sdk` is missing. Separately, the summary reads `model`, `sdk`, CPU, memory and ART fields by global first-key lookup; unrelated values can be reported as the benchmark device.

AndroidX BenchmarkData 1.5.0 source and its generated JSON example place device fields under root `context`, with the Android SDK at `context.build.version.sdk`. Benchmark results are a separate root `benchmarks` collection.

## Goals

1. Require the exact root `context` object, then its direct `build` and `version` child objects, before accepting the SDK floor (API 33).
2. Read build identity from `context.build`, API from `context.build.version`, and CPU/memory/ART fields from `context`.
3. Reject misleading root-level or benchmark-level decoy fields rather than silently accepting or reporting them.
4. Preserve the existing POSIX/AWK-only phone-runner environment; add no runtime dependency.
5. Keep Macrobenchmark cases, metrics, compilation modes, iteration count, APK variants, instrumentation, app sources and measured workload unchanged.

## Approaches considered

1. **Keep global key searches and add more string checks.** Small diff, but it leaves JSON ownership ambiguous and does not reliably bind values to their parent object.
2. **Use a general-purpose JSON dependency or external parser.** This would add an unavailable-by-default runtime dependency to the phone shell bundle and is unnecessary for the limited object extraction required.
3. **Chosen: add a small quote/escape-aware AWK direct-object-member extractor.** It returns only a named direct child object, respects JSON string escapes and brace nesting, and can be reused for eligibility and summary metadata without changing the shell environment.

## Constraints and non-goals

- Do not modify Android app code, the Java Macrobenchmark workload, Gradle configuration or dependencies.
- Do not alter the benchmark's current physical-device requirements, comparison arms, metrics, profiles or timings.
- Do not run Gradle, build, CI, instrumentation, LADB or a physical-device benchmark.
- Keep metadata unavailable/malformed explicit; never substitute unrelated JSON fields.
- This is benchmark-integrity work, not an application performance optimization. No speedup is claimed.

## Verification

Extend the existing fake-device shell fixture with (a) a root-level `build.version.sdk` decoy while root `context` has no valid build, which must be rejected before a report is accepted, and (b) root-level device metadata decoys while the valid context remains present, which must not appear in the summary. Extend the static contract for the direct-object extraction and context-scoped fields. Re-fetch all edited files and inspect the remote diff. Full shell fixture execution remains unavailable through the connected GitHub file API; report that limitation accurately.
