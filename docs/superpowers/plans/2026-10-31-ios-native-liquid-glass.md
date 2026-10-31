# iOS Native Liquid Glass — Phase 1 Implementation Plan

> **For agentic workers:** Use the superpowers test-driven-development workflow. Steps use checkbox syntax.

**Goal:** Allow Nuvio's existing native iOS page sheet to reveal Apple's system Liquid Glass on iOS 26+ when the Bottom Sheets surface is enabled.

**Architecture:** Keep the native UISheetPresentationController and its Compose content. Remove the opaque background only when iOS 26+ and the effective Bottom Sheets setting permit system glass; preserve current backgrounds on older iOS and when disabled.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.13.0-beta01, Kotlin/Native UIKit interop, UIKit UIModalPresentationPageSheet.

**Spec:** docs/superpowers/specs/2026-10-31-ios-native-liquid-glass-design.md

## Global Constraints

- Android continues using the existing Kyant Backdrop / RenderEffect path.
- iOS 26+ native glass is used only at native UIKit/SwiftUI boundaries.
- iOS 25 and earlier retain the current sheet background.
- Preserve theme/local composition propagation, sheet detents, dismissal and drag-handle behaviour.
- No dependencies, global overlays, or settings migrations.

## Review Focus

- iOS 25: keep the configured opaque sheet background.
- iOS 26 with global or per-surface glass disabled: keep the configured opaque background.
- iOS 26 with glass enabled: both the Compose host and sheet controller must be transparent.
- Settings changed while the sheet is already open: update the native host background without recreating the presentation.
- Repeated sheet presentation/dismissal: do not retain a stale controller or delegate.

---

### Task 1: Use the native system sheet material

**Files:**
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NativeBottomSheet.kt
- Modify: composeApp/src/iosMain/kotlin/com/nuvio/app/core/ui/NativeBottomSheet.ios.kt
- Create: composeApp/src/commonTest/kotlin/com/nuvio/app/core/ui/NativeBottomSheetGlassTest.kt

**Interfaces:**
- Add shouldUseNativeLiquidGlassSheetBackground(operatingSystemMajorVersion: Long, surfaceGlassEnabled: Boolean): Boolean.
- The iOS actual passes the current OS major version and isLiquidGlassSurfaceEffectivelyEnabled(BOTTOM_SHEETS).

- [ ] Add tests for iOS 25/26+ and enabled/disabled setting combinations.
- [ ] Implement the OS-and-setting predicate in common code.
- [ ] On iOS, use Color.Transparent and a non-opaque controller view only when the predicate is true.
- [ ] Keep a live reference to the presented controller so a settings change updates its background without dismissing/re-presenting it.
- [ ] Verify the complete diff and run common tests plus an iOS simulator build when an Xcode runner is available.
