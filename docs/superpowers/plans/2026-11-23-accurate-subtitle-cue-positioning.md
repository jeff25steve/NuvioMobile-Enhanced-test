# Accurate Subtitle Cue Positioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve Media3 cue geometry and embedded styling in Nuvio's custom subtitle overlay, anchored to the actual video frame.

**Architecture:** Map Media3 Cue geometry, window color and text-size metadata into the existing StyledSubtitleBlock model, then use a commonMain Compose Layout to measure and place each cue relative to SubtitleVideoBoundsPx. Bitmap cues, vertical-writing cues and numbered-line cues stay in the native subtitle output.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform commonMain, AndroidX Media3 1.11.1 Cue, existing Android host tests.

**Spec:** `docs/superpowers/specs/2026-11-23-accurate-subtitle-cue-positioning-design.md`

## Global Constraints

- Keep the work on `main-build-testing`; do not force-push.
- Use existing Media3 1.11.1 and existing Compose APIs; no dependency upgrades.
- Do not change MPV ASS/SSA rendering or subtitle appearance settings.
- Preserve native rendering for bitmap, vertical-writing and numbered-line cues.
- No Gradle tasks, tests, compilation, builds, CI, APK/AAB, benchmarks or device playback without explicit authorization.
- Tests must be added first in source, but execution is prohibited by the current no-build instruction; report this limitation plainly.

## Review Focus

- Position/line values that are unset, NaN, infinite, negative or greater than 1 must not create invalid layout coordinates.
- Explicit END/MIDDLE anchors near viewport edges must not push cue boxes outside the viewport.
- An unset cue size with an explicit position must shrink to the space available around its anchor.
- Multiple simultaneous cues with different placement or text alignment must remain separate.
- Bitmap, vertical-writing and numbered-line cues must continue through the native renderer.

---

### Task 1: Preserve cue geometry and classify native fallbacks

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitleCues.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/SubtitleCuePresenter.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/StyledSubtitleCuesTest.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/SubtitleCuePresenterTest.kt`

**Interfaces:**
- Produce: `StyledSubtitleBlock.positionX`, `positionXAnchor`, `positionY`, `positionYAnchor`, `widthFraction`, `textAlignment`, `windowColor`, `textSizeValue` and `textSizeType`.
- Preserve existing `vertical` and `horizontal` fields as fallback placement for default and ASS cues.

- [ ] Add common tests for anchor/width calculations and grouping by the full placement key.
- [ ] Add Android host tests for Media3 Cue position, line, anchors, size and textAlignment mapping; verify bitmap, vertical and numbered-line cues remain native.
- [ ] Implement metadata mapping and viewport-bounded width defaults.
- [ ] Update ASS parsing to populate textAlignment from its existing alignment enum without changing output.
- [ ] Static-review all new fallback paths; do not run Gradle tests under the current authorization boundary.
- [ ] Commit as `fix(player): preserve media3 subtitle cue geometry`.

### Task 2: Render cue geometry in the shared Compose overlay

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitle.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitleCues.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/StyledSubtitleCuesTest.kt`

**Interfaces:**
- Consume: the geometry fields produced by Task 1.
- Produce: exact measured child placement in `StyledSubtitleOverlay`, preserving bottom-centre fallback and ASS enum alignment.

- [ ] Add pure placement tests for START/MIDDLE/END anchors at left, centre and right, and top/middle/bottom fractional lines.
- [ ] Add layout logic that measures the cue within its width fraction, computes anchor offsets from measured child size, and clamps the final box to the viewport.
- [ ] Preserve current bottom/top/middle fallback insets for cues without fractional coordinates.
- [ ] Preserve existing text styling, outlines, shadows, file colors and aspect-ratio sizing.
- [ ] Static-review the layout constraints and all unsupported cue fallbacks; do not run Gradle tests under the current authorization boundary.
- [ ] Commit as `feat(player): render exact subtitle cue placement`.

### Task 3: Update existing engineering notes and verify remote source

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/player-stream-startup-optimization-2026-11-19.md`

- [ ] Record the concrete bug, Media3 1.11.1 Cue semantics, chosen implementation, unsupported cue fallbacks and source links.
- [x] Record tests added but not executed and the explicit no-build boundary.
- [ ] Re-fetch every changed file from the remote branch and statically verify metadata mapping, exact placement, fallback routing and docs.
- [ ] Commit as `docs(player): record accurate subtitle cue rendering`.

## Verification

The required Gradle test command is intentionally not run because the user has not authorized builds or CI. Completion evidence is limited to GitHub remote re-fetch, diff review and static source checks. No compilation, test-pass or runtime claim may be made.
