# iOS Native Liquid Glass Adoption

## Intent

Make Nuvio's designated glass surfaces use genuine Apple Liquid Glass on supported iOS versions while preserving the shared Compose feature model and Android renderer.

## Architecture

- Android remains on the existing local Kyant Backdrop / RenderEffect renderer.
- iOS 26 and later use native SwiftUI/UIKit materials for surfaces that are already owned by native iOS containers or controls.
- iOS versions before 26 retain the existing compatible Compose/native fallback.
- Compose surfaces are migrated individually only when their full foreground content, hit testing, accessibility and lifecycle can be hosted correctly by UIKit/SwiftUI. A global UIKit overlay is not an acceptable substitute because it can cover or blur Compose foreground content.

## Existing native iOS implementation

- NativeNavContentView already uses native SwiftUI TabView and NavigationStack on iOS 26+, allowing the system to render navigation glass.
- NuvioGlassTabBar / NuvioSegmentedTabBar already use UIVisualEffectView backed by UIGlassEffect.
- Profile switching already uses a native SwiftUI popover.
- NativeBottomSheet.ios.kt already presents a native UIModalPresentationPageSheet; its opaque Compose/controller backgrounds currently cover the system sheet material.

## Phase 1 scope

- Let the native page-sheet surface show through on iOS 26+ when the effective BOTTOM_SHEETS glass setting is enabled.
- Keep the native sheet opaque when the master switch or surface switch is disabled.
- Keep the existing background on iOS 25 and earlier.
- Keep the Compose sheet content, theme propagation, detents, dismissal delegate, drag handle and lifecycle behaviour unchanged.
- Do not change Android code or the shared Kyant renderer.

## Approaches

1. Enable RenderEffect globally on iOS: rejected. The current Kyant renderer is based on Android RenderEffect / runtime shaders and is not an iOS native Liquid Glass backend.
2. Put a global UIKit glass overlay over Compose: rejected. The overlay can obscure or blur Compose foreground controls and create touch/accessibility/z-order regressions.
3. Adopt native glass at existing native boundaries, then migrate individual controls: selected. It uses the same native framework path as Nuvio's current iOS navigation and preserves Compose for shared screen content.

## Constraints and non-goals

- No new dependencies.
- Do not move or duplicate settings ownership.
- Do not change Android appearance or renderer behaviour.
- Do not apply glass to passive content cards simply to increase coverage; follow Apple's guidance to reserve glass for functional/navigation layers.
- Native material appearance is controlled by iOS. Existing Android-specific blur/refraction tuning does not have exact one-to-one UIKit equivalents and must not be faked with custom iOS blur recipes.
- Subsequent surface migrations must be separate, reviewable steps with platform-specific tests and lifecycle/accessibility checks.
