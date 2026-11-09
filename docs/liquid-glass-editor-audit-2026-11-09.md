# Liquid Glass Editor audit — 9 November 2026

## Scope and repository state

Inspected `jeff25steve/NuvioMobile-Enhanced-test`, branch `main-build-testing`, at `b51cd8d671ed2f2a4b15db48a6e23be39cc3fd0e` (2026-11-09 10:54 UTC). The earlier `pr/liquid-glass-editor-final-20261101` branch was confirmed by the project owner as the intended historical Liquid Glass source. Its editor/surface model is present on the current branch; its older component-specific Profile and Supporter surface controls were deliberately consolidated later.

Local checkout state and uncommitted local work are not exposed by the connected GitHub interface and remain unverified. No builds, build-triggering tests, or CI workflows were run, per instruction.

## Confirmed findings

1. `SettingsCard` in `SettingsComponents.kt` calculated the effective Settings glass state and a glass-specific shape but did not apply `liquidGlassForSurface`; it painted an opaque `tokens.colors.surface` and used `tokens.shapes.card`. Thus shared Settings groups/cards could not show the configured backdrop effect.
2. `PROFILE_WATCH_TIME` and `SUPPORTER_MEMBERSHIP` existed in the earlier 26 October implementation (commit `6dc8fcd0bd`) with distinct override properties, recipes, defaults and editor previews. Commit `288bdcc5cc` removed them and routed both production components to the generic `SETTINGS` surface. This was an intentional consolidation, but it prevented independent tuning.
3. Settings root and standalone Settings background capture depended solely on the generic `SETTINGS` toggle. Restoring the two independent surfaces without widening the shared source-capture gate would leave their glass components with no backdrop whenever the generic Settings surface was disabled.
4. Kyant Backdrop 2.0.1 has a Skiko implementation whose platform capability functions report supported; its Android implementation gates RenderEffect at API 31 and RuntimeShader at API 33. The shared Compose renderer is not Apple's native SwiftUI/UIKit Liquid Glass. No iOS native overlay or platform API changes were made.

## Changes in this cycle

- Restored `PROFILE_WATCH_TIME` and `SUPPORTER_MEMBERSHIP` as appended enum values, with the historical enabled defaults, non-icon capability, independent serializable overrides and original component recipes. Appending preserves the current enum member order.
- Routed the Profile Watch Time row and Supporter Membership card back to their dedicated settings. Their preview entry points invoke the real production components using preview-local overrides, not static lookalike content.
- Added an opt-in additional-surface backdrop predicate. The main Settings screen opts into Profile Watch Time and Supporter Membership; the standalone Supporters & Contributors page opts into only Supporter Membership. Unrelated standalone Settings pages do not capture a backdrop just because those independent surfaces are enabled.
- Attached the glass renderer to `SettingsCard`, removed its opaque fill while glass is active, and applied the resolved glass shape. The non-glass color/shape fallback remains on the pre-existing path.
- Added host-test cases for independent persistence, default recipes and backdrop-capture gating.
- No dependencies, website/backend settings, navigation, player code, Android native code or iOS native UI were changed.

## Verification and limitations

- Changes were authored through GitHub's remote repository interface; no local checkout was available.
- Static review of changed files and the final commit diff is the only verification performed in this cycle.
- Host tests were updated but **not executed**. No compilation, Android/iOS build, device verification or CI run was performed.
- Before release, run the targeted Android host tests and Android build, then visually check Profile Watch Time, Supporter Membership and generic Settings cards with each surface toggled independently. Also verify existing saved JSON values and platform-specific glass fallback behavior.

## Starting point for the next cycle

Use the resulting commit on `main-build-testing` as the baseline. First inspect the live branch head and final diff. After explicit build/test authorization, run the focused host tests and Android build; then validate rendering on Android and separately confirm iOS fallback/native distinctions.