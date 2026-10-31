# iOS Native Liquid Glass Adoption

## Intent

Make Nuvio's designated functional glass surfaces use genuine Apple Liquid Glass on iOS 26 and later. Preserve the shared Compose feature logic and callbacks where practical, the existing Android renderer, per-surface user choices, accessibility, and older-iOS fallback behavior.

The target is feature parity in intent and platform-native behavior, not pixel-for-pixel parity with Android's custom renderer.

## Current architecture and confirmed implementation

### Native iOS surfaces already using Apple's design

- `NativeNavContentView` uses SwiftUI `TabView` and `NavigationStack` on the iOS 26 native-navigation path. These standard system components receive Liquid Glass automatically.
- `NuvioGlassTabBar` / `NuvioSegmentedTabBar` use UIKit `UIVisualEffectView` backed by `UIGlassEffect()`.
- The profile switcher is already presented as a native SwiftUI popover from the tab bar.
- `NativeBottomSheet.ios.kt` presents a native `UIModalPresentationPageSheet`. Commit `cdbfe0b14076a065589db0705b22d9ebbeb7b8b1` makes the Compose host and presented controller transparent on iOS 26+ only when the effective `BOTTOM_SHEETS` setting is enabled, allowing the system sheet material to show through. Older iOS and disabled settings retain the configured background.

### Compose surfaces still lacking native Liquid Glass on iOS

- Player: `PlayerLiquidGlass.kt` uses the shared Compose/Backdrop renderer, while `PlayerLiquidGlass.ios.kt` currently returns no video-frame backdrop. Player controls therefore do not receive real native Liquid Glass yet.
- Player speed selector: `PlayerSpeedPopup.kt` is a Compose popup, not a native iOS menu.
- Detail actions and selectors: shared Compose components such as `DetailActionButtons.kt`, `DetailTrailersSection.kt`, and related detail headers/navigation controls rely on the shared renderer or Compose surfaces.
- Stream picker and search controls: shared Compose surfaces such as `ProviderFilterRow.kt` and the search controls remain Compose-rendered.
- Library controls: the library filter/search/calendar areas are Compose-rendered. Passive cloud content cards are intentionally disabled by default and should stay that way unless a surface is genuinely a functional control.

The iOS Compose fallback is not Apple's Liquid Glass. Do not describe it as native glass.

## Selected architecture

1. Keep native system-owned UI native. Prefer SwiftUI/UIKit navigation bars, tab bars, menus, popovers, and sheets. Their system materials adapt to OS, appearance, accessibility preferences, focus, and overlap.
2. For player controls over video, investigate a narrowly scoped native UIKit material layer attached to the existing native MPV player view. Compose owns the visible controls and callbacks; UIKit owns only the native glass material beneath those controls. This is viable only if simulator testing proves the effect view samples the video layer and leaves Compose foreground content sharp.
3. For other Compose surfaces, migrate the complete bounded functional control into a native host where practical. A UIKit overlay containing only a glass background is not sufficient: it covers Compose foreground content. Avoid a generic global overlay or one nested Compose controller per small card.
4. Prefer standard native system controls such as `UIMenu` for discrete option menus where semantics and behavior match. Keep shared Compose menus when the content or interaction cannot be represented faithfully by a native menu.
5. Retain Compose for shared business logic, playback state, and callbacks. Native views are presentation adapters; they must not duplicate state or introduce a second persistence layer.

## Material and settings mapping

- iOS 26+: use Apple's `UIGlassEffect` / SwiftUI `.glassEffect()` and system presentation materials, not custom blur, RenderEffect, custom shaders, or a visual imitation.
- iOS 25 and earlier: preserve the existing non-native fallback.
- Use the native `regular` and `clear` materials only where the actual component and content justify them. Apple controls the final dynamic rendering; do not translate Android blur/refraction sliders into invented iOS blur values.
- Preserve existing saved per-surface settings. Do not delete, migrate, or silently rewrite stored values merely because an individual iOS surface cannot expose the same low-level tuning.
- For iOS controls that cannot meaningfully use Android-specific tuning, make unsupported controls unavailable or clearly platform-scoped rather than implying they affect Apple's material. Keep the enable/disable surface choice functional.
- Keep passive media/content cards non-glass by default. Glass belongs to the functional/navigation layer.

## Goals

- Designated functional glass surfaces use genuine native materials on supported iOS.
- Existing iOS navigation, profile switching, and native sheet behavior remain native.
- Controls remain legible, accessible, tappable, and correctly layered.
- Per-surface enablement and global enablement are respected.
- Android rendering, settings, and behavior remain unchanged.

## Non-goals

- Rewriting the entire Nuvio application in SwiftUI.
- Replacing Compose business logic, navigation state, playback state, or settings ownership wholesale.
- Enabling a UIKit overlay above the whole Compose canvas.
- Making passive content cards glass merely to maximize coverage.
- Faking arbitrary Kyant blur/refraction parameters with UIKit blur or tint stacks.

## Approaches considered

1. **Enable Kyant/RenderEffect for iOS:** rejected. The current renderer is not Apple's Liquid Glass backend and the platform API support differs.
2. **Put a generic UIKit glass view over Compose:** rejected. Overlay placement covers Compose content in the same region, risks touch/accessibility/z-order regressions, and cannot selectively blur only the background of a Compose component.
3. **Use native system UI and bounded per-surface native hosts:** selected. It matches Nuvio's existing native navigation/tab-bar implementation, preserves Compose logic, and limits UIKit interop to components that can be proven safe.

## Constraints

- No new dependency unless a specific gap is demonstrated and approved by the existing architecture.
- No changes to Android rendering.
- No changes to website/backend-owned configuration.
- No global UIKit overlays, duplicated app state, or blanket Compose-to-SwiftUI rewrite.
- iOS 25 and earlier retain a usable fallback.
- Respect VoiceOver, Dynamic Type, Reduce Transparency, Increased Contrast, dark/light appearance, iPad layout, rotation, multitasking, and player lifecycle.
- Every migration batch is independently reviewable and tested on iOS. If native layering fails, stop that surface and choose a different hosting boundary rather than shipping a visual imitation.

## Verification requirements

- iOS 26+ simulator: real native glass appears, follows enabled settings, and samples the intended background.
- iOS 25 simulator: fallback remains legible and functional without referencing unavailable APIs at runtime.
- Global and per-surface glass disabled: original fallback remains.
- Light/dark appearance, Reduce Transparency, Increased Contrast, VoiceOver, Dynamic Type, iPad, rotation, sheet presentation/dismissal, and player/PiP transitions are checked where relevant.
- Compose foreground remains sharp and interactive above any native material.
- Android tests/build are run as regression checks for shared code changes.

## Player video backdrop — explicit validation gate

The current iOS player renders video into a custom `CAMetalLayer` (`MetalLayer` in `iosApp/iosApp/Player/MetalLayer.swift`) with `framebufferOnly = true`. The `MPVPlayerViewController` adds this layer directly to its root view's layer tree. Apple documents `CAMetalLayer` as a Metal drawable presentation layer and `UIVisualEffectView` as an effect that acts on content layered behind it, but those API descriptions do not guarantee the exact sampling/refracting behavior for Nuvio's Metal video layer and current Compose/UIKit view hierarchy.

Therefore, placing a `UIVisualEffectView(UIGlassEffect)` above the MPV layer is a hypothesis, not an approved production design. Before implementing a player surface registry or changing player controls:

1. Validate on iOS 26+ that a native glass view attached inside the real MPV controller actually samples the currently presented video, rather than showing a flat/black/transparent material.
2. Validate that Compose controls remain sharp above it and all Compose touch, gesture, VoiceOver and accessibility behavior remains intact.
3. Validate source replacement, orientation/size changes, mini-player/PiP transitions, and view-controller disposal remove stale effect views.
4. If the sampling or layering check fails, stop this approach. Evaluate a different bounded native host that contains both its material and foreground control content, or retain the existing fallback for that surface until a safe route is proven. Do not use private Core Animation backdrop APIs or copy full video frames to CPU memory as a workaround.

The plan must keep this validation as a gate before any production player control is migrated.
