# Accurate Media3 Subtitle Cue Rendering — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve Media3 cue geometry and embedded styling in Nuvio's custom subtitle overlay, anchored to the actual fitted video frame.

**Architecture:** Map Media3 Cue geometry, window color and text-size metadata into `StyledSubtitleBlock`. Use a commonMain Compose `Layout` to measure and place each cue relative to `SubtitleVideoBoundsPx`. Preserve native rendering for bitmap, vertical-writing and numbered-line cues.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.13.0-beta01, AndroidX Media3 1.11.1 Cue, existing Android host/common tests.

**Spec:** `docs/superpowers/specs/2026-11-23-accurate-subtitle-cue-positioning-design.md`

## Global Constraints

- Keep the work on `main-build-testing`; do not force-push.
- Use existing Media3 1.11.1 and existing Compose APIs; no dependency upgrades.
- Do not change MPV ASS/SSA rendering, subtitle selection, timing or appearance settings.
- Preserve native rendering for bitmap, vertical-writing and numbered-line cues.
- No Gradle tasks, tests, compilation, builds, CI, APK/AAB, benchmarks or device playback without explicit authorization.
- Regression tests are added but intentionally unexecuted under the current no-build instruction.

## Review Focus

- START/MIDDLE/END anchors at both viewport edges.
- Unset anchors must match Media3 SubtitlePainter's START default; unset text alignment must default to CENTER.
- An unset cue size must measure text width and constrain wrapping to the available region around its anchor; it must not be stretched to a full-width cue box.
- Explicit cue size must be bounded by the anchor and viewport.
- Fit letterbox offsets and Zoom negative crop offsets must match Media3 AspectRatioFrameLayout and be clipped to the player container.
- Do not place mobile subtitle cues outside the fitted video bounds into black bars; that is a separate TV-oriented enhancement request.
- Embedded absolute/fractional text sizes and cue window colors must be preserved; absent embedded styles must use the user's configured defaults.
- NaN/infinite/out-of-range cue metadata must not produce invalid layout coordinates.
- Different geometry, color or font-size cues must not be merged.
- Bitmap, vertical-writing and numbered-line cues must continue through the native renderer.

### Task 1: Preserve Media3 cue geometry and classify native fallbacks

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitleCues.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/SubtitleCuePresenter.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/StyledSubtitleCuesTest.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/SubtitleCuePresenterTest.kt`

- [x] Add common tests for anchor offsets, bounded cue widths and grouping by the full geometry/style key.
- [x] Add Android host tests for Media3 position, line, anchors, size and text alignment.
- [x] Keep bitmap, vertical-writing and numbered-line cues on the native renderer; add fallback tests.
- [x] Implement cue metadata mapping, Media3's unset-anchor defaults, measured-width behavior and invalid-value handling.
- [x] Preserve ASS/SSA alignment mapping and parser behavior.
- [x] Commit source and regression tests. Tests were not executed.

### Task 2: Render cues relative to the fitted video frame

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitle.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAppearance.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleAppearanceTest.kt`

- [x] Add Fit/Fill/Zoom tests for video-frame width, height, left and top offsets.
- [x] Extend `SubtitleVideoBoundsPx` with letterbox/crop offsets.
- [x] Measure cue boxes against video-frame dimensions and position x/y against video-frame origin.
- [x] Clip Zoom overflow to the player container while retaining negative crop offsets.
- [x] Apply subtitle insets, default font scaling and fractional cue text sizes relative to the fitted video frame.
- [x] Preserve existing custom font, outline, shadow and file-color behavior.
- [x] Commit layout and video-bound source changes. Tests were not executed.

### Task 3: Preserve embedded cue window colors and font sizes

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitleCues.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitle.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/SubtitleCuePresenter.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/StyledSubtitleCuesTest.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/SubtitleCuePresenterTest.kt`

- [x] Map `Cue.windowColor` only when `windowColorSet` is true; otherwise use the user's configured background.
- [x] Map absolute-pixel, fractional and fractional-ignore-padding cue text sizes into the common model and resolve them against video-frame height.
- [x] Include embedded styling in grouping keys so visually different cues remain independent.
- [x] Add regression test source for window colors and all supported text-size modes. Tests were not executed.

### Task 4: Update existing engineering notes and verify remote source

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/player-stream-startup-optimization-2026-11-19.md`
- Modify: this plan and the linked spec.

- [x] Record the concrete rendering gaps, Media3 1.11.1 semantics, fitted-frame behavior, sources and unsupported native fallbacks.
- [x] Deduplicate overlapping MPV cycles in the two engineering documents; keep detailed history in the codebase notes and a short pointer in the player-specific document.
- [x] Record tests added but not executed and the explicit no-build boundary.
- [x] Re-fetch every changed file from the remote branch and statically verify metadata mapping, layout math, fallback routing and docs.
- [x] Commit the notes and plan/spec update.

## Verification

The required Gradle test command is intentionally not run because the user has not authorized builds or CI. Completion evidence is limited to GitHub remote re-fetch, diff review and static source checks. No compilation, test-pass or runtime claim may be made.
