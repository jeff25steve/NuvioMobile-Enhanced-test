# libmpv source and post-filter video fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct mpv bit-depth metadata and distinguish source, post-filter and estimated-output video details in Playback Info.

**Architecture:** Keep parsing in the existing common `PlayerMediaInfo.kt` pure helpers. Extend the Android libmpv JSON builder with source/post-filter fields and estimated output FPS, then render only known values in the shared Playback Info modal.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.13.0-beta01, Android libmpv properties, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-23-mpv-output-fidelity-design.md`

## Global Constraints

- No dependency changes; Media3 remains `1.12.0-beta01`.
- No playback, decoder, shader, filter, HDR-selection, buffering, iOS or ExoPlayer rendering changes.
- Treat `video-params` as decoded/source metadata and `video-out-params` as post-filter metadata.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback without explicit authorization.

## Review Focus

- Pixel formats such as `p010le` and `p016le` must not be misread because their digits contain a leading zero.
- Packed RGB formats such as `rgb48le` and `rgba64le` represent 16 bits per component, not 48/64-bit component depth.
- Hardware-only/unknown formats must remain unknown rather than defaulting to 8-bit.
- Post-filter `video-out-params` may be unavailable before a frame or when mpv has no video; omit those rows.
- `estimated-vf-fps` is a recent output estimate and can be unavailable or unstable during seeking/frame drops; display it only when finite and positive.

---

### Task 1: Expand pixel-format depth parsing

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerMediaInfo.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerMediaInfoTest.kt`
- Modify: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlaybackFormatMetadataTest.kt`

**Interfaces:**
- Keep `mpvBitDepth(pixelFormat: String): Int?`.
- Add `mpvPlaybackEstimatedFps(value: Double?): Double?`, returning only finite positive values.

- [ ] Add common tests for 8/9/10/12/14/16-bit formats, P010/P016, RGB48/RGBA64, and unknown hardware formats.
- [ ] Add estimated-FPS tests for positive finite, null, zero, negative, NaN and infinity.
- [ ] Implement explicit suffix/alias recognition; return null for unsupported formats.
- [ ] Do not run Gradle tests without authorization.

### Task 2: Separate source and post-filter metadata

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlaybackInfoModal.kt`

**Interfaces:**
- Preserve `bitDepth` and `colorRange` as source/decoded values.
- Add JSON fields `sourcePixelFormat`, `outputBitDepth`, `outputColorRange`.
- Keep `pixelFormat` as the existing post-filter pixel format for compatibility.
- Use `video-params/pixelformat` for source and `video-out-params/pixelformat` / `video-out-params/colorlevels` for post-filter metadata.

- [ ] Emit the source pixel format and post-filter bit depth/color range.
- [ ] Label the UI rows as Source Pixel Format, Source Bit Depth, Source Color Range, Post-filter Pixel Format, Post-filter Bit Depth and Post-filter Color Range.
- [ ] Keep unknown values omitted and leave ExoPlayer's existing fields/labels unchanged.

### Task 3: Clarify frame-rate diagnostics and update notes

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlaybackInfoModal.kt`
- Modify: `docs/engineering/codebase-improvement-notes.md`

**Interfaces:**
- Add JSON field `estimatedOutputFps` from mpv `estimated-vf-fps`, normalized by `mpvPlaybackEstimatedFps`.
- Preserve `fps` as the existing container FPS for libmpv and selected-format FPS for ExoPlayer.

- [ ] Emit estimated output FPS only when finite and positive.
- [ ] Label libmpv's existing value Container FPS and add Estimated Output FPS when available; keep ExoPlayer's Frame Rate label.
- [ ] Append one new section to the active engineering notes with source evidence and the verification boundary; do not duplicate the previous HDR/format-fidelity record.
- [ ] Re-fetch every changed file and the branch head for static review.
- [ ] Do not run Gradle tests, compilation, builds, CI, benchmarks or device playback without authorization.

## Verification boundary

Regression test sources are required, but remain unexecuted until Gradle execution is authorized. Report source review separately from runtime verification.
