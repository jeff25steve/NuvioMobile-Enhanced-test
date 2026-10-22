# In-App One-Handed Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Nuvio's in-app one-handed mode behave consistently on Android and iOS, with profile-matched Library hold recognition, Samsung-inspired positioning and resize controls, a safe blurred backdrop, and intact playback/navigation.

**Architecture:** Keep one shared in-memory mode state. Apply a single transform to the existing interactive app UI; do not mount a second active copy of navigation/screens to simulate a live backdrop. Prefer a safe live backdrop only if the current rendering layer supports it without duplicate composition or state/network work. Otherwise use an ephemeral in-memory snapshot if there is a supported cross-platform path, then a theme-aware blurred/frosted fallback. Player and navigation ownership remain unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, UIKit native tab integration, existing Liquid Glass and navigation components.

**Spec:** `docs/superpowers/specs/2026-10-21-in-app-one-handed-mode-design.md`

## Global Constraints

- Keep work on branch `one-hand-operation`; inspect the head and current file blob SHA before each GitHub contents write.
- Use the existing nav architecture and restore no behavior outside the one-handed feature.
- The normal Library tap navigates normally; Library press-and-hold toggles one-handed mode without a popup.
- Match the profile hold's gesture recognition and cancellation semantics. Preserve the profile popup and drag-to-select gestures.
- Outside taps must be consumed and must neither dismiss the mode nor activate hidden UI. Exit via tapping the thin top handle or Library hold.
- The reduced app stays interactive; placement can switch left/right and resizing is continuous and bounded.
- Android and iOS share the same handle/arrow controls and interaction rules. Preserve the normal navigation bar; any one-handed-only iOS adjustment must be verified not to regress ordinary tab navigation.
- Disable one-handed mode before video/trailer presentation; release backdrop resources and do not restore mode automatically after playback.
- No overlay/accessibility permission, background service, polling, persistent screenshot, telemetry, or network transfer of UI pixels/state.
- Do not use GitHub Actions/workflows; run local tests/builds if a local checkout becomes available and report any verification limitation.
- Context7 must be consulted before each code edit for the API/library involved; use Exa when current platform limitations remain unclear.
- Do not create a PR.

## Review Focus

- Library tap vs hold vs drag: one hold toggles once, tap navigates once, movement cancellation returns to drag-to-select, RTL works.
- Profile hold: popup/drag selection still behaves exactly as before and never toggles one-handed mode.
- Outside taps: no mode dismissal, no hidden content activation, and no interception of the top handle or side-switch arrow.
- Resizing: pointer stays captured while the surface edge moves; lower/upper viewport bounds remain valid under rotation and compact dimensions.
- Backdrop and playback: no duplicate navigation composition, stale snapshot, captured protected video, or automatic reactivation after player exit.
- iOS parity: verify how the SwiftUI `TabView`/`NuvioGlassTabBar` overlay behaves relative to the Compose transform; it is currently outside that transform in source.

---

### Task 1: Reuse the profile hold gesture pattern for Library

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/jelly/JellyTabs.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/ui/FloatingNavigationBarGestureTest.kt`

- [x] Move Library hold recognition onto its own tab target using the same `detectDragGesturesAfterLongPress` mechanism and long-press haptic as `ProfileSwitcherTab` (source updated; runtime behavior still needs test execution).
- [x] Remove the separate parent-level long-press recognizer for Library; the parent gesture loop suppresses click/drag commit after a child hold.
- [x] Retain the accessibility `onLongClick` semantic and keep the current selected index stable after hold.
- [ ] Existing source tests cover tap, hold, RTL, and drag-to-select, including Profile hold remaining profile-only; test execution remains pending.

### Task 2: Make outside taps inert and preserve explicit exit

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedModeState.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/OneHandedPresentationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/OneHandedModeStateTest.kt`

- [x] Keep the outer pointer gate consuming taps outside the reduced rounded surface but do not call a dismiss callback.
- [x] Remove obsolete outside-dismiss state and callback plumbing where no longer used.
- [x] Updated tests to assert outside taps keep mode enabled and hidden content untouched; explicit exit and scale state are covered in source tests (not yet executed).

### Task 3: Add bounded live resize of the reduced surface

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedModeState.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/OneHandedModeStateTest.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/OneHandedPresentationTest.kt`

- [x] Add a small in-memory scale value with a documented lower and upper bound and a controller update method.
- [x] Match the reference video with a slim 40×3dp pill on a larger transparent target above the surface; tapping exits, vertical dragging resizes continuously.
- [x] Add a small directional side-switch arrow outside the surface at mid-height; keep both floating controls excluded from the outside-touch interceptor.
- [x] Keep the pointer detector alive while the scale state changes, cap dimensions to the viewport, and maintain correct side anchoring/hit testing (needs runtime verification).
- [x] Add lower/upper scale-bound, continuous resize-drag, and state-preservation test cases (not yet executed).
- [x] Match the video’s approximate 78% scale and tighter 16dp corner radius (not yet visually/device-verified).

### Task 4: Use the safest available backdrop on both platforms

**Files:**
- Inspect and modify only if supported: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt` and existing platform capture/rendering utilities.

- [x] Confirmed the existing `NuvioBackdrop`/`GraphicsLayer.record` mechanism and current Kyant `drawBackdrop` blur API from repository code and official library documentation.
- [x] Wired a live backdrop to the already-composed app layer without mounting a duplicate composition; visually/performance behavior is not yet device-verified.
- [x] Used live layer capture instead of adding a separate persisted snapshot path; unsupported rendering targets keep the existing theme-aware fallback.
- [x] Retained the theme-aware frosted gradient fallback when the renderer reports no live effect support.
- [x] No polling/refresh loop, screenshot persistence, new permissions, background work, or feature network activity was added.

### Task 5: Final regression review

**Files:** Only directly affected source and tests from Tasks 1–4.

- [x] Compared the focused implementation diff and verified that one-handed-specific native tab-bar visibility gates are absent; Liquid Glass source files were not changed.
- [ ] Run the focused tests and platform build only if a local checkout/build environment is available; do not use CI/workflows.
- [ ] Resolve/test the iOS native navigation-bar scale mismatch without changing the original bar behavior when one-handed mode is off.
- [x] No PR was created and no workflow/release config was touched by this implementation pass.
- [ ] Report exactly what was checked and what remains unverified.
