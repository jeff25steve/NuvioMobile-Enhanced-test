# iOS Native Liquid Glass — Full Rollout Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make all designated functional Liquid Glass surfaces use Apple's real native material on supported iOS while preserving Nuvio's Compose logic and Android renderer.

**Architecture:** Keep system navigation, popovers and sheets native. Migrate remaining Compose functional surfaces in bounded groups to UIKit/SwiftUI hosts, only where the foreground and glass can be layered correctly. Start with player controls over the existing native MPV video view, because that background already exists as a UIKit view.

**Tech Stack:** Compose Multiplatform 1.13.0-beta01, Kotlin Multiplatform, Kotlin/Native UIKit interop, SwiftUI, UIKit `UIGlassEffect`, `UIVisualEffectView`, `UIMenu`.

**Spec:** `docs/superpowers/specs/2026-10-31-ios-native-liquid-glass-design.md`

## Global Constraints

- Android remains on the existing Kyant Backdrop / RenderEffect renderer.
- iOS 26+ uses Apple's real SwiftUI/UIKit system materials; no custom iOS blur/shader imitation.
- iOS 25 and earlier retain the current compatible fallback.
- Preserve global and per-surface enablement, stored values, business logic, callbacks, and profile isolation.
- Do not make passive content cards glass by default.
- No global UIKit overlay above the Compose canvas.
- No new dependencies or website/backend settings changes.
- Compose controls must remain sharp, accessible and interactive above native material.

## Review Focus

- iOS 25: no runtime reference to iOS 26-only material APIs; fallback remains legible.
- iOS 26 with global or per-surface glass disabled: current non-glass appearance is preserved.
- Player view is recreated, rotated, resized, minimized, or transitions to/from PiP: native glass views are removed or repositioned without stale references.
- Compose controls move/fade/scroll or popups appear: glass frames remain aligned and never cover foreground controls or intercept touch.
- Reduce Transparency, Increased Contrast, VoiceOver, Dynamic Type, light/dark appearance, and iPad are respected.

---

### Task 1: Native page-sheet material (code committed; verification outstanding)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NativeBottomSheet.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/nuvio/app/core/ui/NativeBottomSheet.ios.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/core/ui/NativeBottomSheetGlassTest.kt`

**Interfaces:**
- `shouldUseNativeLiquidGlassSheetBackground(operatingSystemMajorVersion: Long, surfaceGlassEnabled: Boolean): Boolean`.

- [x] Add the OS/setting predicate and conditional transparent host/controller backgrounds.
- [x] Add the live controller reference so settings changes update the presented view without re-presenting.
- [x] Add common tests for iOS 25/26/27 and enabled/disabled combinations.
- [ ] Run common tests and iOS 26+ / iOS 25 simulator verification. No Xcode runner is currently available in this environment.

Commit: `cdbfe0b14076a065589db0705b22d9ebbeb7b8b1`.

### Task 2: Prove native Liquid Glass can sample the actual MPV video layer (spike gate)

**Files:**
- Test-only addition if supported by the existing iOS test target: `iosApp/iosAppTests/PlayerNativeGlassSamplingTests.swift`
- Inspect only: `iosApp/iosApp/Player/MPVPlayerBridge.swift`, `iosApp/iosApp/Player/MetalLayer.swift`, `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt`

**Known facts:**
- The player view owns a custom `CAMetalLayer` and uses `framebufferOnly = true`.
- Compose controls are rendered above the `UIKitViewController` interop surface.
- UIKit's `UIVisualEffectView` is intended to affect content behind it, but the framework documentation does not promise that this exact Metal/Compose composition is sampled as desired.

- [ ] Build a minimal native iOS 26+ proof using the actual MPV player controller and one `UIVisualEffectView(effect: UIGlassEffect())` added above the video layer, without touching production controls.
- [ ] Run on an iOS 26+ simulator and, when possible, a physical device with moving video. Confirm the glass shows the live video rather than black, clear, or a frozen surface.
- [ ] Confirm the Compose controls above the effect remain sharp, hittable, accessible and correctly layered.
- [ ] Check resize/orientation and controller teardown; ensure the effect view is removed with the player view.
- [ ] Record result and screenshot/video evidence. If sampling or z-order fails, stop this route and revise the player design; do not add a production registry or copy video frames to CPU memory.

**Gate:** No production player overlay or control changes until this spike is verified on iOS 26+.

### Task 3: Player native glass surface registry and UIKit video-layer host (only after Task 2 passes)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerNativeGlassRegistry.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerLiquidGlass.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerLiquidGlass.ios.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerNativeGlassRegistryTest.kt`

**Interfaces:**
- `PlayerNativeGlassSurfaceKey`: stable per-composition key for one control surface.
- `PlayerNativeGlassSurface`: window-space bounds in physical pixels, corner radius in pixels, tint RGBA, native material choice (`regular` or `clear`), and interaction preference.
- `PlayerNativeGlassRegistry.update(key, surface)`, `remove(key)`, and read-only `surfaces`.
- `LocalPlayerNativeGlassRegistry`: scoped to one player composition; never a process-global registry.
- `isNativePlayerGlassAvailable(): Boolean`: true only on iOS 26+; false on Android and earlier iOS.

- [ ] Write tests for updating/replacing/removing registered surfaces, empty/zero-sized bounds, non-finite geometry, and independent keys.
- [ ] Run the new common tests and confirm the expected failures before implementation.
- [ ] Implement the registry and composition local; create it per PlayerScreen composition.
- [ ] Register each existing `playerLiquidGlass` modifier's measured bounds and effective settings; remove registrations on disposal.
- [ ] In `PlayerEngine.ios.kt`, update/reuse native `UIVisualEffectView` children only on iOS 26+, attached to the verified MPV player controller view.
- [ ] Ensure native material views do not intercept touches or enter the accessibility tree; Compose remains the foreground semantic/control layer.
- [ ] Test source change, controls hide/show, rotation, resize, background/foreground, and PiP cleanup.
- [ ] Run common tests, Android regression tests, iOS simulator compile/build and iOS UI verification when an Xcode runner is available.


### Task 4: Native player speed menu

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSpeedPopup.kt`
- Create: `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerSpeedPopup.ios.kt`
- Create: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSpeedPopup.android.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerSpeedPopupTest.kt`

**Interfaces:**
- Keep the existing speed options, display formatting, current-speed selection tolerance, `onSpeedSelected`, and `onDismiss` callback contract.
- iOS 26+ uses a native option menu or a bounded `UIGlassEffect` popup with native foreground items; older iOS and Android retain Compose behavior.

- [ ] Test option ordering, labels, selected speed tolerance, callback values and dismissal semantics.
- [ ] Verify failing tests before implementation.
- [ ] Extract only platform-neutral option data into common code.
- [ ] Implement the native iOS menu without moving playback-speed state out of the existing player runtime.
- [ ] Preserve keyboard, pointer, VoiceOver, focus and dismissal behavior.
- [ ] Run tests and iOS/Android builds when available.

### Task 5: Detail functional surfaces

**Files to inspect and migrate in separate commits:**
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailActionButtons.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailFloatingHeader.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailTrailersSection.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailEpisodeNavigator.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt`

**Interfaces:**
- Preserve each existing `LiquidGlassSurface` identifier, click callback, preview interaction, and stored per-surface setting.
- Use native system navigation/menu/popover controls when their semantics match; use a custom UIKit host only when it can contain the foreground content too.

- [ ] Inventory actual runtime call sites and distinguish functional controls from passive content.
- [ ] Add focused tests for visibility, setting enablement, callback routing and native host layout.
- [ ] Migrate one component/group at a time; do not bulk-replace Compose controls with inert native decorations.
- [ ] Verify VoiceOver and Dynamic Type for each native group.

### Task 6: Stream picker, search and library controls

**Files to inspect and migrate in separate commits:**
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/ProviderFilterRow.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/SearchScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt`

**Interfaces:**
- Preserve current stream selection/filter state and library/search callbacks.
- Keep cloud content cards disabled by default; only functional filters, search and calendar controls are candidates for native glass.
- Preserve saved surface overrides and independent surface defaults.

- [ ] Inventory each live call site before edits.
- [ ] Add regression tests for filter state, selected values, search input, sorting, calendar state and enable/disable settings as applicable.
- [ ] Migrate one bounded group at a time using native UIKit/SwiftUI material and foreground controls.
- [ ] Verify scroll, keyboard, focus, VoiceOver and screen-size behavior.

### Task 7: Platform-aware Liquid Glass settings editor

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/LiquidGlassSurfacesSettingsSheet.kt`
- Modify only if needed: platform capability helpers in `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/glass/`
- Test: common tests under `composeApp/src/commonTest/kotlin/com/nuvio/app/core/ui/glass/`

**Interfaces:**
- Preserve all serialized per-surface values and settings compatibility.
- Define explicit iOS capabilities for enablement, material, shape and interaction.
- Do not claim iOS honors Android-only blur/refraction sliders.

- [ ] Test the capability matrix for iOS 25, iOS 26+ and Android.
- [ ] Show only controls that have a genuine platform effect; explain system-controlled material where useful.
- [ ] Never clear or migrate stored Android-specific values when editing settings on iOS.
- [ ] Keep translations within the existing resource system and avoid duplicate platform settings.

### Task 8: Final cross-platform regression gate

- [ ] Run the relevant common test suite.
- [ ] Run Android host tests and build; confirm Android renderer output is unchanged.
- [ ] Run iOS simulator tests/build on iOS 25 and iOS 26+.
- [ ] Verify player/video layering, navigation, profile popover, sheets, menus, details, stream picker, search, library, settings, and accessibility.
- [ ] Inspect complete diff, branch head, and compare against the integration base.
- [ ] Do not create a PR or merge without explicit approval.
