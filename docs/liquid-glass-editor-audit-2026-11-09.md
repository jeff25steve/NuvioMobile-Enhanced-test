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

## Follow-up review — 9 November 2026, 16:49 UTC

### Live repository state

- Reconfirmed branch `main-build-testing` at `7083816b7cf70ccebd5e9c4b75f203ccd893676a`; immediate parent `9263506a668000c4c47765cb733d584f9af284cb`; the two implementation commits are the only changes ahead of the previously inspected `b51cd8d` baseline.
- The restored `LiquidGlassSurface` enum now contains 28 values. Static source inspection confirmed both new surfaces are covered by the recipe resolver, settings `forSurface` / `withSurface` dispatch, editor title mapping, and the editor preview routing. They are included automatically in the standalone editor list because they are not members of an existing composite preview group.
- The latest commit scopes standalone Supporters & Contributors backdrop capture to its membership card; the multi-page Settings host still opts into both Profile Watch Time and Supporter Membership.

### Additional findings — not changed

1. **Profile Watch Time text treatment:** `ProfileWatchTimeRow` reads Nuvio text tokens before any per-surface contrast scope and passes explicit text styles and colors to its labels. The page-level contrast scope is controlled by the generic `SETTINGS` surface. When `PROFILE_WATCH_TIME` is enabled independently while `SETTINGS` is disabled, the row therefore does not receive the intended per-surface brightened tokens; explicit text styles also need their shadow applied deliberately rather than assuming an outer `LocalTextStyle` will be merged. Confirm visually and add a targeted regression test before changing it.
2. **Backdrop capture while unrelated Settings pages are active:** `SettingsScreen` passes both extra surfaces to `shouldCaptureLiquidGlassSettingsBackdrop` regardless of its current `SettingsPage`. Since both restored surfaces default enabled, the background graphics layer can be recorded on other Settings pages where neither card is visible. The capture intentionally contains only the background, not scrolling controls; the actual performance cost is unmeasured. Consider page-aware capture only with a regression test for navigation between Root, Profile and Supporters/Contributors pages.

### Build and test authorization / limitation

- The owner explicitly authorized a build at 16:49 UTC.
- A build could not be started in this runtime: no local repository checkout or `gradlew` exists; no Gradle executable, Android SDK, or Rust toolchain is installed; the Java runtime is 21 while the repository's full-debug workflow provisions Java 17; and `git ls-remote` failed because the environment could not resolve `github.com`.
- The inspected full-debug workflow's build command is `./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore --stacktrace`. The connected GitHub actions tools expose run inspection and rerun operations, but no workflow-dispatch action. No PR was created merely to trigger CI.
- No host tests, compilation, Android build, iOS build, or device rendering checks were run. Remote static inspection is not a substitute for these checks.

### Next verification sequence

1. Run `./gradlew :androidApp:testDebugUnitTest :composeApp:allTests` only if those tasks exist for the configured targets; first inspect the Gradle task graph in a real checkout.
2. Run the repository full-debug workflow command above on a runner with JDK 17, Android SDK and Rust/NDK dependencies.
3. Add/verify the Profile Watch Time local text treatment and make Settings backdrop capture page-aware, preserving independent toggles and root/profile/supporter navigation.
4. Visually inspect Profile Watch Time, Supporter Membership, and generic Settings cards with their surface toggles independently enabled/disabled. Check iOS separately; the shared Backdrop renderer is not Apple's native SwiftUI/UIKit Liquid Glass.

