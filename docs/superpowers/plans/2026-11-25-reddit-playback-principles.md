# Reddit-inspired playback improvements — Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Smooth Nuvio's visible playback timeline at display refresh rate without changing the playback engine or broadening 4 Hz snapshot updates.

**Architecture:** Keep Media3 1.8.0 and the current player architecture. Add a pure interpolation contract and a local Compose frame-clock state inside the common timeline and legacy seekbar.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, Compose Runtime, Kotlin common tests, AndroidX Media3 1.8.0 custom AAR family.

**Spec:** `docs/superpowers/specs/2026-11-25-reddit-playback-principles-design.md`

## Global Constraints

- Work only on `main-build-testing`; no force-push.
- Preserve the existing 250 ms player snapshot loop for the rest of the player state.
- Frame-driven state must remain inside timeline components.
- Do not call player APIs or issue seeks from frame callbacks.
- Do not change Media3 1.8.0, the custom AAR set, minSdk 24 or dependency exclusions.
- Do not add player pooling, eager source prefetching or MediaSource sharing.
- No Gradle, tests, compilation, builds, CI, benchmarks or device runs without separate authorization.

## Review Focus

- Pause/resume: interpolation stops when `isPlaying` becomes false and resumes from the latest snapshot.
- Seek/drag: pointer interaction suspends the frame ticker and leaves the scrub target authoritative.
- Playback speed: 0.5x, 1.5x and fractional rates advance the UI at the corresponding rate.
- Duration: known duration clamps the displayed value; unknown duration does not invent an upper bound.
- Invalid values: NaN/infinite/non-positive speed, negative elapsed time and arithmetic overflow must not produce an invalid or decreasing position.
- Composition: ticker cancellation follows the timeline composable's lifecycle; no frame ticker remains when the timeline leaves composition.

---

### Task 1: Define and test the interpolation contract

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerTimeline.kt`
- Create: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerTimelineInterpolationTest.kt`

**Interface:**
- Produces: `shouldAnimatePlaybackTimeline(isPlaying: Boolean, isInteracting: Boolean, durationMs: Long, playbackSpeed: Float): Boolean` and `interpolatePlaybackPositionMs(positionMs: Long, elapsedNanos: Long, playbackSpeed: Float, durationMs: Long): Long`

- [x] Add common tests for 1x/fractional speed, paused/interacting/invalid eligibility, negative elapsed time, known/unknown duration, negative position and Long overflow. Tests are authored but unexecuted.
- [x] Implement saturating interpolation using elapsed nanoseconds and positive finite playback speed.
- [x] Preserve unknown duration as unbounded while treating negative position as zero.

### Task 2: Add local frame-synchronised state to timeline components

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerTimeline.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt`

**Interface:**
- Produces: `rememberFrameSynchronizedPositionMs(positionMs, durationMs, isPlaying, playbackSpeed, isInteracting): Long`
- Consumes: the pure `shouldAnimatePlaybackTimeline(...)` and `interpolatePlaybackPositionMs(...)` policy functions.

- [x] Use `produceState` and `withFrameNanos` to interpolate only while playing, with known duration and a finite positive speed.
- [x] Suspend frame updates while the user is pressing/dragging the timeline; use the current displayed scrub position without interpolation.
- [x] Wire the helper into standard `PlayerTimeline` slider/preview and legacy `PlayerSeekBar` slider/preview/time display.
- [x] Pass the current snapshot `isPlaying` and `playbackSpeed` from `ProgressControls` into `PlayerSeekBar`.
- [x] Do not change snapshot polling, gesture callbacks, seek dispatch or progress persistence.

### Task 3: Record the architecture decision and inspect the complete diff

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Create: `docs/superpowers/specs/2026-11-25-reddit-playback-principles-design.md`
- Create: `docs/superpowers/plans/2026-11-25-reddit-playback-principles.md`

- [x] Record the Reddit-derived principles that are adopted, deferred or rejected and explain why the Media3 custom AAR upgrade is blocked.
- [x] Re-fetch each changed file from `main-build-testing` and inspect imports, types, all call sites, dependency alignment and static compatibility guards.
- [x] Record source-only verification accurately. No test/build/CI/device result may be claimed.

## Execution record

- Plan created against remote `main-build-testing` SHA `0258adbeaf0e6862d501b349f1486d90bb98ecba`.
- Test/build/CI execution remains prohibited in this task.


## Verification boundary and execution record

- Source, test and documentation changes are committed to main-build-testing. Static review re-fetched the latest timeline source, legacy seekbar source, test file, dependency catalog and Media3 AAR compatibility guard.
- The test file was added before the interpolation and eligibility implementation, but the test suite was not run; no failing RED result or passing test result is claimed.
- No Gradle task, compiler, test, build, CI, benchmark, APK/AAB generation or device playback was run. Compile/runtime verification remains outstanding.
- Current implementation commits include the common frame interpolation, legacy seekbar wiring, seekbar declaration-order correction, interaction/bounds correction, and animation-eligibility policy. See the Git history for individual commit IDs.


### Task 4: Bounded codec-specific retry

**Files:**
- Create: composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/FailedMediaCodecSelector.android.kt
- Create: composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/FailedMediaCodecSelectorTest.kt
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt
- Modify: docs/engineering/codebase-improvement-notes.md

- [x] Extract failed codec identities from Media3 1.8.0 decode and initialization exceptions.
- [x] Keep per-source audio/video exclusions separate and thread-safe.
- [x] Cap codec-specific exclusions at two per playback track.
- [x] Apply exclusions to the active MediaCodecSelector query, preserving original candidates when filtering would leave none.
- [x] Preserve selected audio/subtitle tracks, playback speed and position before retrying.
- [x] Preserve Dolby Vision soft-match and Dolby-free audio selection wrapper ordering.
- [x] Add Robolectric host-test source for identity extraction, filtering, empty-result fallback and bounded exclusions. Tests are authored but unexecuted.

## Additional verification boundary

- AndroidX Media 1.8.0 source was inspected for MediaCodecSelector, MediaCodecInfo, MediaCodecDecoderException, MediaCodecRenderer.DecoderInitializationException and MediaCodecUtil behavior.
- The implementation intentionally uses only codec MIME/name identity; secure/tunnelling requirements remain properties of the current selector query. It does not mistake MediaCodecInfo capability flags for the original decoder query.
- No Gradle task, compilation, tests, build, CI, benchmark or device run was performed. Both the common timeline tests and Android host codec tests remain unexecuted.
