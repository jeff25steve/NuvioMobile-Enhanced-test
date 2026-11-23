# Android libmpv independent scaling filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Expose live, independently configurable upscaling, downscaling and chroma scaling filters for Android libmpv while preserving existing renderer defaults.

**Architecture:** Add a shared filter enum and a pure profile-aware resolver in PlayerModels. Persist the three selected enum names through the existing profile-scoped PlayerSettingsStorage and sync payload. Pass settings into NuvioLibmpvView, apply resolved values after the selected profile at initialization and reapply them after profile changes. Add three rows and choice dialogs to the existing Android video settings section.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, libmpv options API. Android libmpv dependency remains 0.2.1.

**Spec:** `docs/superpowers/specs/2026-11-23-android-mpv-scaling-filters-design.md`

## Global Constraints

- No dependency changes or AAR upgrades.
- No default visual behavior change; new selections default to Default/profile-managed.
- No ExoPlayer, iOS, decoder, video-output, buffering, interpolation, frame-rate or HDR policy changes.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback without authorization.

## Review Focus

- Switching render quality must not erase explicit up/down/chroma overrides.
- Returning a filter to Default must resolve to the current profile's actual built-in value.
- cscale Default follows the effective upscaler, not a hardcoded filter.
- Invalid stored enum names must fall back to Default.
- Runtime filter changes must be serialized on the existing mpv dispatcher and must not reload media.

---

### Task 1: Define filter choices and profile-aware resolution

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerModels.kt`
- Create: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/MpvScalingOptionsTest.kt`

**Interfaces:**
- `AndroidLibmpvScalingFilter` enum with `mpvValue: String?`, `label`, and `description`.
- `mpvScalingOptionValues(quality, upscaler, downscaler, chromaScaler): Map<String, String>`.

- [x] Add tests for Balanced, High Quality and Performance default resolution.
- [x] Add tests for each independent override and cscale following the resolved upscaler.
- [x] Implement the enum and resolver using upstream mpv built-in profile values.
- [x] Do not run Gradle tasks without authorization.

### Task 2: Persist, profile-scope and sync three filter selections

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.ios.kt`

- [x] Add three Default-valued UI state fields and repository state.
- [x] Load invalid or missing values as Default.
- [x] Add setters that publish and persist only on change.
- [x] Add Android profile-scoped preference keys and include them in profile key lists.
- [x] Include all three settings in Android exportToSyncPayload and replaceFromSyncPayload; keep iOS storage contract parity without exposing Android-only controls there.
- [x] Keep iOS settings storage parity, but do not expose the controls on iOS.

### Task 3: Apply filter settings at initialization and live

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`

- [x] Pass three selections into NuvioLibmpvView.
- [x] Resolve and apply `scale`, `dscale` and `cscale` after the initial render profile in initOptions.
- [x] Add one dispatcher-serialized setter that applies the three writable `options/<name>` properties.
- [x] Reapply resolved filter values after every render-quality profile transition.
- [x] Keep media loaded; do not invoke loadfile/reload.

### Task 4: Expose the controls in Android settings and document

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackVideoSettings.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `docs/engineering/codebase-improvement-notes.md`

- [x] Add three navigation rows and existing choice dialogs.
- [x] Add English title/description resources, with fallback to default resources for locales not yet translated.
- [x] Append one concise section to existing playback notes, not a duplicate file.
- [x] Re-fetch changed source and test files and statically review all settings sync and initialization paths.
- [x] Do not run Gradle tasks, builds or CI.

## Verification boundary

The regression test source is authored but remains unexecuted because Gradle test tasks are build operations and the user has explicitly withheld build authorization. Static review and remote source re-fetch do not substitute for compilation or device visual verification.


## Execution record

- Added profile-aware filter resolution and common regression cases.
- Added profile-scoped persistence, settings sync, Android settings controls and iOS storage contract parity.
- Applied filters after profile initialization and re-applied them live after profile/filter changes without reloading media.
- Updated the existing engineering notes with upstream evidence, mobile GPU caveats and the verification boundary.
- Test sources were authored but not executed. No Gradle tasks, compilation, build, CI, benchmark or device playback was started.
- Static review and remote source re-fetch are the only verification completed; runtime compatibility and visual quality remain unverified pending authorization.
