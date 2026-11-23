# Android manual frame-rate matching follows playback speed — Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Keep Android's explicit Always frame-rate request synchronized with the actual playback cadence at non-1.0x speeds.

**Architecture:** Keep the existing AlwaysVideoFrameRateController; calculate its requested rate through a pure helper and refresh it through the existing state-update path. Do not add dependencies or change renderer ownership.

**Tech Stack:** Kotlin, Android Surface API, AndroidX Media3 1.12.0-beta01, Kotlin host tests.

**Spec:** docs/superpowers/specs/2026-11-23-android-frame-rate-speed-design.md

## Global Constraints

- Work only on main-build-testing.
- Preserve system preference and existing non-seamless eligibility policy.
- Do not change libmpv, HDR, decoder selection, renderer, buffering or dependencies.
- Do not run Gradle, compilation, builds, CI, benchmarks or device tests without authorization.

## Review Focus

- Unknown source frame rate must clear rather than preserve a stale manual request.
- Invalid or non-positive playback speed must clear the request.
- Fractional source rates must retain precision.
- Multiplication overflow must not reach Surface.setFrameRate.
- Speed changes must update the manual request without requiring a track change.

---

### Task 1: Resolve playback-rate-aware Surface frame rate

**Files:**
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlaybackFrameRateMatching.android.kt
- Test: composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlaybackFrameRateMatchingTest.kt

**Interfaces:**
- Produces: playbackSurfaceFrameRate(mediaFrameRate: Float, playbackSpeed: Float): Float
- Produces: AlwaysVideoFrameRateController.updateVideoFrameRate(frameRate: Float, playbackSpeed: Float)

- [ ] Add tests for 24×1.5=36, 23.976×1.25=29.97, 60×0.5=30, invalid values and overflow.
- [ ] Implement the pure resolver; return zero for invalid inputs or invalid products.
- [ ] Update the controller to store the resolved playback frame rate.

### Task 2: Wire speed changes into the manual controller

**Files:**
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt

**Interfaces:**
- Consumes: playbackSurfaceFrameRate via the controller's two-argument update method.
- Existing: updateAlwaysFrameRateState()

- [ ] Refresh the controller rate in updateAlwaysFrameRateState from currentVideoFrameRate and exoPlayer.playbackParameters.speed.
- [ ] Route format changes and controller creation through that shared update path.
- [ ] Invoke the shared update path from onPlaybackParametersChanged.
- [ ] Preserve the current Media3 strategy toggle ordering and existing playback eligibility gate.

### Task 3: Update the existing engineering record and review the complete change

**Files:**
- Modify: docs/engineering/codebase-improvement-notes.md
- Create: this design and plan document.

- [ ] Add one new entry to the existing active engineering notes; do not create a duplicate engineering handoff.
- [ ] Review all call sites for the controller's changed method signature.
- [ ] Verify tests and source statically. Gradle tests/builds/CI/device checks are prohibited until separately authorized.
- [ ] Commit the complete change and verify the remote branch head.
