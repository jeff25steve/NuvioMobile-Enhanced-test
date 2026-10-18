# Liquid Glass Navigation Controls — Design Specification

**Status:** Proposed design; awaiting review before implementation  
**Date:** 2026-10-13  
**Target branch:** `feature/navigation-liquid-glass`  
**Reference implementations:** [Kyant0/AndroidLiquidGlass, KMP branch](https://github.com/Kyant0/AndroidLiquidGlass/tree/kmp) and [Kashif-E/KMPLiquidGlass](https://github.com/Kashif-E/KMPLiquidGlass/tree/master)

## Goal

Fix the visual and interaction defects in Nuvio's opt-in Liquid Glass floating navigation bar, and expose useful glass-effect controls without changing navigation behavior, icon/label content, tab ordering, gestures, existing style/position/glow settings, or the regular Enhanced navigation appearance.

## Confirmed implementation findings

- `FloatingNavigationBar.kt` rendered the active `JellyTabRow` across the full bar when the Liquid Glass selection backdrop was present, producing extra accent-coloured words/icons. Its `LayerBackdrop` was also attached to a wrapper containing the rendered glass surface. The upstream implementation instead captures a separate invisible tab-content layer as the selection lens backdrop source. Capture that content separately, clip the visible active row to the moving selected pill, and keep the inactive row as the only full-width visible row. Preserve the selected tab's existing accent treatment inside the pill; slider adjustments must not alter navigation icon/label colours or feed the rendered glass surface back into its own backdrop.
- Kyant's `lens` accepts non-negative refraction height and amount in **pixels**, returns without a lens when either value is zero, and supports Boolean `depthEffect` and `chromaticAberration`. The existing implementation hard-codes the bar lens at 24 dp × 24 dp and a touch-scaled selection lens at 10 dp × 14 dp, and applies a fixed 8 dp blur to the bar. Slider values must be converted from dp to pixels at the actual drawing density. Following Kyant's adjustable Glass Playground for user-tunable controls, the configured selection lens remains active at rest; only its highlight/shadow and jelly deformation remain touch-animated. Increasing a slider must never invert its meaning.
- The existing theme-settings repository/storage already scopes relevant settings per profile and includes settings in sync payloads. New effect controls should follow that path instead of introducing a separate preference system.
- The project is Kotlin Multiplatform. Kyant's KMP implementation reports runtime-shader support on its Skia target (used by iOS); Android runtime shaders require API 33+. Existing unsupported-platform fallback must remain intact.

## UX and controls

When **Liquid Glass** is enabled for a non-classic floating navigation bar, show two visually distinct groups in the existing Nuvio bottom sheet:

### Floating bar

- Corner radius: 0–50%, default **50%** (capsule).
- Blur radius: 0–32 dp, default **1 dp** (matching the live preview).
- Refraction height: 0–48 dp, default **11 dp** (matching the live preview).
- Refraction amount: 0–48 dp, default **23 dp** (matching the live preview).
- Chromatic aberration: switch, default **on**.
- Depth effect: switch, default **on**.
- Glass tint opacity: 0–100%, default **0%**, applied to Nuvio's active surface token (not the device's system light/dark setting).

### Selected-tab lens

- Corner radius: 0–50%, default **50%**.
- Blur radius: 0–24 dp, default **1 dp** (matching the live preview).
- Refraction height: 0–32 dp, default **6 dp**.
- Refraction amount: 0–32 dp, default **11 dp**.
- Chromatic aberration: switch, default **on**, matching the current selected lens.
- Depth effect: switch, default **on**.
- Glass tint opacity: 0–100%, default **0%**, matching the current live preview.

The selected-tab lens group also has independent switches for **Affect tab icons** (default on) and **Affect floating bar** (default on), which gate the optional refraction source layers independently. Each group's title has a **reset-to-default icon button** using the app's existing icon/button style. Resetting one group restores only that group's defaults in the editor; those defaults are saved on exit with any other pending edits. It must not change the other group or any navigation settings. Slider values are displayed with units/percentages where applicable. Use standard Material 3 sliders and accessible labelled switches, consistent with the current Nuvio settings typography, spacing, theme colours, and touch targets. Standard sliders should inherit the current Nuvio theme's Material 3 primary/accent colour (the app builds `colorScheme.primary` from the active theme palette); reset icons remain neutral action icons rather than additional accent-coloured indicators. Do not reuse the hue/saturation/brightness gradient tracks from the custom-theme colour editor for these numeric settings. Slider controls inherit the theme accent; changing their values must not alter navigation icon/label colours. Preserve the current selected tab's accent treatment only within the moving pill, with inactive icons/labels remaining muted. The existing animated theme-accent glow (`drawJellyGlow`) remains a separate nav-bar effect and should continue to follow its existing animation/position; don't link glow colour to slider values or flood the whole glass surface with the accent colour. When Liquid Glass is disabled, preserve the regular Enhanced navigation styling. Controls are visible only while Liquid Glass is enabled, and retain the existing shader-support/unsupported-state behavior.

The settings-sheet preview and the running floating bar update as the user changes an effect-group control. Keep the new effect-group sliders, chromatic-aberration/depth switches, and reset actions as in-memory drafts while the Navigation Bar settings sheet is open; do not write these effect settings to persistent storage while the user is still editing. Preserve the existing immediate persistence behavior of the pre-existing Glass glow and Liquid Glass enable switches. On every exit path (Done, back/system back, swipe dismissal, or tap outside), commit both effect groups together before dismissing. There is no separate Save button. Reset immediately restores that group to its defined defaults in the editor, without touching the other group; the reset is committed on exit along with any other pending edits. If the sheet is cancelled/dismissed, this means saving—not discarding—the pending values. Avoid preferences writes or full-profile sync-signature serialization on every slider frame. After both effect groups are persisted, emit a single commit revision so profile sync observes the saved values. Reset restores the defined constants, not the last-saved custom values.

## Implementation boundaries

Expected production files (confirm exact call sites again immediately before editing):

- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`: constrain active labels/icons to the animated selected pill and pass effect settings to the two surfaces.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/glass/GlassBarSurface.kt`: parameterize existing Kyant effects and expose a single default configuration per surface; preserve the normal frosted fallback.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/NavigationBarSettingsSheet.kt` and `NavigationBarPreview.kt`: controls, reset buttons, independent defaults, and live preview.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsRepository.kt` and `ThemeSettingsStorage.kt`, plus Android/iOS storage implementations: profile-scoped state, safe loading defaults, persistence and sync payload integration.
- Focused tests under `composeApp/src/androidHostTest/kotlin/com/nuvio/app/` for settings encoding/decoding, clamping and defaults; add a regression assertion where the existing UI-test setup can reliably exercise the active-row clipping.

Persist the two effect configurations independently, with backward-compatible defaults when an existing profile has no keys. Malformed or out-of-range stored values must safely fall back to validated defaults; no migration of unrelated settings is required. Use existing serialization utilities/dependencies and established profile-scoped storage conventions. Do not change website-managed configuration.

## Compatibility and regression constraints

- Keep the opt-in toggle, all existing navigation styles/positions, glow, compact/expanded behavior, drag-to-select, click targets, RTL mapping, tab identities/glyphs/labels, and Android/iOS navigation destinations unchanged. The Liquid Glass selected-content overlay is clipped to the selection shape; the regular non-Liquid-Glass styling remains unchanged.
- When refraction height or amount is zero, disable that lens effect (as Kyant's API does); zero blur disables blur. Ranges/defaults must be monotonic and bounded.
- Preserve Android API 32-and-below frosted fallback and existing runtime-shader support gating; no new dependency.
- Do not alter the native iOS navigation bar or the separate native Liquid Glass tab-bar behavior.
- Avoid per-frame disk writes. Never persist effect settings globally across profiles.
- Do not start GitHub Actions for verification while the Actions quota is exhausted. Build/test verification must be reported honestly; source inspection is not a substitute for a successful build or device check.

## Acceptance criteria

1. With Liquid Glass enabled, the normal bar shows one set of tab labels/icons. Only the selected pill receives active treatment; text is never duplicated or globally accent-tinted by the hidden/reference layer.
2. Changing each slider/switch changes its corresponding real surface effect in the correct direction, visibly and live in the preview and navigation bar (including selection-lens refraction at rest); it never changes the other surface's settings.
3. Both reset icon buttons restore all values in their own group to the constants above and leave the other group's current custom values unchanged.
4. Settings survive app restart and remain isolated per profile; sync export/import preserves both groups, and old/malformed payloads load safe defaults.
5. Existing normal navigation behavior and frosted fallbacks remain intact.
6. Focused tests are added and run where available; Android/iOS builds and real-device rendering are explicitly marked unverified unless successfully executed.
