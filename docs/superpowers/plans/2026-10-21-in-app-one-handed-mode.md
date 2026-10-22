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
- Outside taps must be consumed and must neither dismiss the mode nor activate hidden UI. Exit only via the explicit exit control or Library hold.
- The reduced app stays interactive; placement can switch left/right and resizing is continuous and bounded.
- Android and iOS share the same visible controls, visual treatment, and interaction rules; platform-specific implementation is acceptable only where required.
- Disable one-handed mode before video/trailer presentation; release backdrop resources and do not restore mode automatically after playback.
- No overlay/accessibility permission, background service, polling, persistent screenshot, telemetry, or network transfer of UI pixels/state.
- Do not use GitHub Actions/workflows; run local tests/builds if a local checkout becomes available and report any verification limitation.
- Context7 must be consulted before each code edit for the API/library involved; use Exa when current platform limitations remain unclear.
- Do not create a PR.

## Review Focus

- Library tap vs hold vs drag: one hold toggles once, tap navigates once, movement cancellation returns to drag-to-select, RTL works.
- Profile hold: popup/drag selection still behaves exactly as before and never toggles one-handed mode.
- Outside taps: no mode dismissal, no hidden content activation, and no interception of the close/placement/resize handles.
- Resizing: pointer stays captured while the surface edge moves; lower/upper viewport bounds remain valid under rotation and compact dimensions.
- Backdrop and playback: no duplicate navigation composition, stale snapshot, captured protected video, or automatic reactivation after player exit.

---

### Task 1: Reuse the profile hold gesture pattern for Library

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/jelly/JellyTabs.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/ui/FloatingNavigationBarGestureTest.kt`

- [ ] Move Library hold recognition onto its own tab target using the same `detectDragGesturesAfterLongPress` mechanism and long-press haptic as `ProfileSwitcherTab`.
- [ ] Remove the separate parent-level long-press recognizer for Library so it cannot race the target recognizer.
- [ ] Retain the accessibility `onLongClick` semantic and keep the current selected index stable after hold.
- [ ] Verify tests for tap, hold, RTL and drag-to-select; profile hold must still open its popup only.

### Task 2: Make outside taps inert and preserve explicit exit

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedModeState.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/OneHandedPresentationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/OneHandedModeStateTest.kt`

- [ ] Keep the outer pointer gate consuming taps outside the reduced rounded surface but do not call a dismiss callback.
- [ ] Remove obsolete outside-dismiss state and callback plumbing where no longer used.
- [ ] Update tests to require the mode stay enabled after outside tap while hidden content remains untouched; explicit exit must still work.

### Task 3: Add bounded live resize of the reduced surface

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedModeState.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/OneHandedModeStateTest.kt`
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/OneHandedPresentationTest.kt`

- [ ] Add a small in-memory scale value with a documented lower and upper bound and a controller update method.
- [ ] Add an accessible visible drag handle near the top edge of the reduced surface; dragging up enlarges and dragging down reduces it continuously.
- [ ] Keep the pointer detector alive while the scale state changes, cap dimensions to the viewport, and maintain correct side anchoring/hit testing.
- [ ] Cover lower/upper bounds and drag interaction; mode exits and player entry must not reset unrelated app/player state.

### Task 4: Use the safest available backdrop on both platforms

**Files:**
- Inspect and modify only if supported: `composeApp/src/commonMain/kotlin/com/nuvio/app/OneHandedPresentation.kt` and existing platform capture/rendering utilities.

- [ ] Confirm current Compose Multiplatform APIs available to this project for safe layer capture, blur, and release on Android and iOS.
- [ ] Use a live backdrop only if it can be derived from existing rendering without duplicating the active app tree or player surface.
- [ ] Otherwise use a supported ephemeral snapshot, blur/dim it, refresh only at well-defined foreground/page changes, and release on exit/player/disposal.
- [ ] If safe cross-platform capture cannot be established, use a matching frosted/glass fallback rather than inventing unverified capture APIs.
- [ ] Do not add loops, screenshot persistence, permissions, background work, or feature network activity.

### Task 5: Final regression review

**Files:** Only directly affected source and tests from Tasks 1–4.

- [ ] Review the complete branch diff against the current branch base and ensure the navigation bar visibility fix and Liquid Glass code remain intact.
- [ ] Run the focused tests and platform build only if a local checkout/build environment is available; do not use CI/workflows.
- [ ] Verify no PR was created, no release/workflow config changed, and no unrelated files changed.
- [ ] Report exactly what was checked and what remains unverified.
