# Seek-preview precision and cache memory bounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make scrub previews more accurate and cap cached bitmap retention without changing playback-engine behavior.

**Architecture:** Keep the existing Android native-thumbnail feed and custom ExoPlayer frame fallback. Tighten fallback time buckets, reject stale cached-frame reuse, and share a pure Kotlin LRU trimming helper across the two existing caches.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, AndroidX Media3 1.12.0-beta01, Kotlin test.

**Spec:** docs/superpowers/specs/2026-11-23-seek-preview-precision-memory-design.md

## Global Constraints

- Work only on `main-build-testing`; no force-push.
- Do not change Media3 version or add dependencies.
- Preserve native thumbnail feed, custom ExoFrameGrabber, HTTP headers, MPV, iOS, playback buffering, HDR playback output, and frame-rate policy.
- No Gradle tests, compilation, builds, CI, benchmarks, or device playback without explicit authorization.

## Review Focus

- Long HDR or SDR video: fallback bucket resolution stays bounded and no playback output path changes.
- Portrait or unusually large native thumbnails: estimated bitmap bytes trigger eviction even below the 100-entry limit.
- Rapid scrubbing: a completed old request cannot replace the current requested frame with a distant thumbnail.
- Empty, negative, or oversized cache accounting inputs: trimming remains bounded and deterministic.
- Native feed failure: existing fallback handoff and cache behavior remain intact.

---

### Task 1: Make fallback frame selection more accurate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewController.kt`
- Modify: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewLifecycleTest.kt`

**Interfaces:**
- Consumes: `bucketFor(positionMs, durationMs)` and `nearestCached(bucket)`.
- Produces: 1,000–2,000 ms bucket selection and distance-bounded cached fallback.

- [x] Add regression tests for long-title bucket duration, rounding, and maximum cached-frame distance.
- [ ] Verify the tests fail before implementation when test execution is authorized (not run; Gradle test execution remains unauthorized).
- [x] Clamp `MaxBucketMs` to 2,000 ms and expose `seekPreviewBucketDurationMs` for deterministic tests.
- [x] Make `nearestCached` return only frames within 2,000 ms.
- [x] Clear an unrelated old preview while a distant requested frame is decoding; do not publish a completed stale frame as the current preview.
- [ ] Run the targeted and full relevant host-test suite when authorized (not run; no build/test authorization).

### Task 2: Bound bitmap cache retention

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewFrameCache.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewController.kt`
- Create: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewCacheTest.kt`

**Interfaces:**
- Produces: `trimSeekPreviewCache(cache, maxEntries, maxBytes, sizeInBytes)` and `isSeekPreviewFrameWithinDistance(candidatePositionMs, requestedPositionMs, maxDistanceMs)`.
- Both existing caches use a 100-entry ceiling and 24 MiB estimated RGBA budget.

- [x] Add tests for byte-budget eviction, entry-count eviction, oversized frames, invalid sizes, saturated byte totals, and Long-range timestamp distance.
- [ ] Verify the tests fail before implementation when test execution is authorized (not run; Gradle test execution remains unauthorized).
- [x] Implement a pure Kotlin insertion-order trimming helper with saturating byte accumulation.
- [x] Apply it to both native-feed and decoded-frame caches using width × height × 4.
- [ ] Run the targeted and full relevant host-test suite when authorized (not run; no build/test authorization).

### Task 3: Update the existing engineering record

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Create: this plan and the linked design spec.

- [x] Record the concrete changes, source evidence, trade-offs, and exact verification boundary.
- [x] Review the complete remote diff and verify the updated branch ref.
- [x] Do not start builds, tests, CI, benchmarks, or device playback without authorization.


## Execution record

- Implementation committed to `main-build-testing` in four fast-forward commits after base `180acafd15435c3033f06342f3faa582c0b1a17e`; final HEAD at this record: `7807fd46f6a91899306f76ae730c8ea139979ccf`.
- Regression-test source is present. The RED run, GREEN run, host-test suite, and Kotlin compilation remain pending because no Gradle test/build execution was authorized.
- No CI, build, APK/AAB, benchmark, or device playback was started.
- Final review was a read-only author self-review of the complete GitHub compare range; no independent reviewer/subagent facility was available.
