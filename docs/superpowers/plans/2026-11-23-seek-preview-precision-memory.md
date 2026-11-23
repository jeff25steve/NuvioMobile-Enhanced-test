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

- [ ] Add regression tests for long-title bucket duration, rounding, and maximum cached-frame distance.
- [ ] Verify the tests fail before implementation when test execution is authorized.
- [ ] Clamp `MaxBucketMs` to 2,000 ms and expose `seekPreviewBucketDurationMs` for deterministic tests.
- [ ] Make `nearestCached` return only frames within 2,000 ms.
- [ ] Clear an unrelated old preview while a distant requested frame is decoding; do not publish a completed stale frame as the current preview.
- [ ] Run the targeted and full relevant host-test suite when authorized.

### Task 2: Bound bitmap cache retention

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewFrameCache.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewController.kt`
- Create: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewCacheTest.kt`

**Interfaces:**
- Produces: `trimSeekPreviewCache(cache, maxEntries, maxBytes, sizeInBytes)` and `isSeekPreviewFrameWithinDistance(candidatePositionMs, requestedPositionMs, maxDistanceMs)`.
- Both existing caches use a 100-entry ceiling and 24 MiB estimated RGBA budget.

- [ ] Add tests for byte-budget eviction, entry-count eviction, oversized frames, invalid sizes, and Long-range timestamp distance.
- [ ] Verify the tests fail before implementation when test execution is authorized.
- [ ] Implement a pure Kotlin insertion-order trimming helper with saturating byte accumulation.
- [ ] Apply it to both native-feed and decoded-frame caches using width × height × 4.
- [ ] Run the targeted and full relevant host-test suite when authorized.

### Task 3: Update the existing engineering record

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Create: this plan and the linked design spec.

- [ ] Record the concrete changes, source evidence, trade-offs, and exact verification boundary.
- [ ] Review the complete remote diff and verify the updated branch ref.
- [ ] Do not start builds, tests, CI, benchmarks, or device playback without authorization.
