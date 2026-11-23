# ASS/SSA Subtitle Fidelity and Add-on Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Preserve authored ASS/SSA styling while enabling accurate font resizing and opt-in customization, and fix addon subtitle arrival/selection reliability.

**Architecture:** Share ASS override policy in common Kotlin. Apply it in Android libmpv and iOS mpv, while scaling ExoPlayer's libass renderer directly. Separately make addon state observable and defer auto-selection completion until addon loading ends.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Media3 libass compatibility layer, libmpv, Swift bridge, Kotlin test.

**Spec:** `docs/superpowers/specs/2026-11-23-ass-subtitle-fidelity-and-addon-reliability-design.md`

## Global Constraints
- Do not add or upgrade dependencies.
- Preserve saved profiles by defaulting `customizeAss` to false.
- Do not force custom styling on authored ASS/SSA unless explicitly enabled.
- Keep `main-build-testing` as the only target branch; do not force-push.
- Do not run builds, Gradle tests, CI, benchmarks or device playback without authorization.

## Review Focus
- Existing profiles whose serialized appearance JSON does not contain `customizeAss`.
- ASS tracks that change font size repeatedly and then return to the default size.
- Subtitle switches between ASS, SSA, SRT and WebVTT while the same mpv instance is active.
- ExoPlayer player rebuild/release while libass rendering callbacks are registered.
- Addon subtitles arriving after the initial scan while the player is paused or buffering.

---

### Task 1: ASS/SSA scale policy and user opt-in
**Files:** common subtitle appearance model, player controller capability, subtitle modal/panel, Android/iOS player engines and bridge, Swift mpv controller, common tests.
- [x] Add common `assOverride(codec, customizeAss, assScale)` policy.
- [x] Add backward-compatible opt-in preference and mpv-only UI capability.
- [x] Apply scale-only behavior to mpv and font scaling to ExoPlayer libass.
- [x] Add regression test sources for default preservation, scale-only mode, force opt-in and non-ASS isolation.
- [ ] Execute common tests and Android/iOS compilation after authorization.

### Task 2: Addon subtitle state and delayed selection
**Files:** PlayerScreenRuntimeEffects.kt, PlayerScreenRuntimeState.kt, PlayerScreenRuntimeTrackActions.kt, PlayerScreenRuntimeStateTest.kt, PlayerSubtitleRestoreTest.kt.
- [x] Treat only `NoInternet` as offline for independent addon services.
- [x] Make addon state observable to Compose.
- [x] Keep preferred-language selection pending while addon subtitle loading continues.
- [x] Add regression test sources for paused-state updates and late preferred-language results.
- [ ] Execute targeted tests after authorization.

### Task 3: Lifecycle and documentation review
**Files:** PlayerLibassCompat.kt, PlayerEngine.android.kt, engineering notes.
- [x] Remove libass handler scale callback state and release the handler with ExoPlayer.
- [x] Use the full styled-overlay predicate so outline blur works consistently.
- [x] Update the single canonical engineering notes page and this spec/plan.
- [ ] Perform Android/iOS playback visual checks after authorization.

### Task 4: Android tablet subtitle readability
**Files:** SubtitleAudioModels.kt, PlayerEngine.android.kt, SubtitleAppearanceTest.kt, PlayerLibassCompatTest.kt.
- [x] Extend Android font-size range to 12–60sp while preserving iOS 6–40sp.
- [x] Raise Android libmpv font-size ceiling to 183 so 60sp is not clamped.
- [x] Add common range and Android mapping regression test sources.
- [ ] Execute common and Android host tests after authorization.

### Task 5: Renderer efficiency and cross-platform recovery
**Files:** PlayerLibassCompat.kt, PlayerEngine.android.kt, MPVPlayerBridge.swift, PlayerLibassCompatTest.kt.
- [x] Make libass font-scale updates idempotent and skip redundant overlay invalidation.
- [x] Persist and restore ASS customization/scale through iOS mpv device-loss recovery.
- [x] Remove `sub-ass-style-overrides` commands that mpv ignores in `force` mode; retain the already-applied generic `sub-bold` override.
- [x] Add idempotence and non-finite-scale regression test sources.
- [ ] Compile Android/iOS and execute host tests after authorization.

## Verification record
Test files were added but not executed. No Gradle command, build, CI, benchmark or device playback was run.
`PlayerLibassCompatTest`, `SubtitleAppearanceTest` and the iOS Swift source were updated and reviewed remotely. Tests, compilation, builds, CI, benchmarks and device playback were not run. The code is not build-verified.
