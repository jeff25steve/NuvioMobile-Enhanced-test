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
3. Verify the Profile Watch Time text treatment with its surface independently enabled/disabled; evaluate page-aware Settings backdrop capture only with navigation and saveable-state restoration regression coverage.
4. Visually inspect Profile Watch Time, Supporter Membership, and generic Settings cards with their surface toggles independently enabled/disabled. Check iOS separately; the shared Backdrop renderer is not Apple's native SwiftUI/UIKit Liquid Glass.

## Follow-up implementation — 9 November 2026, 23:02 UTC

### Additional omissions confirmed

- The 28-surface model contains 22 surfaces in the four composite preview groups and six standalone entries. The editor's `liquidGlassStandaloneSurfaces` correctly returns Player, Search, Settings, Bottom Sheets, Profile Watch Time and Supporter Membership. The existing common test still expected only the old four entries.
- The two restored standalone entries had no leading icon in `LiquidGlassSurfaceListItem`; all other four top-level entries had a matching icon.
- `LiquidGlassTextContrastScope` changed `LocalTextStyle`, but reused the original Material3 `Typography`. Material3's `Text(style = ...)` uses `LocalTextStyle.current` only as its default; when Nuvio explicitly passes `MaterialTheme.typography.*`, the inherited shadow is not applied.
- `ProfileWatchTimeRow` and `SupporterMembershipCard` used their own surface effects but did not scope their actual text content with `LiquidGlassTextContrastScope`.

### Changes in this cycle

- Added `Schedule` and `Groups` leading icons to Profile Watch Time and Supporter Membership in the standalone editor list.
- Updated the standalone-surface grouping test to include all six ungrouped entries.
- Added a `Typography.withLiquidGlassTextShadow` transformation for all 15 standard and 15 emphasized Material3 styles, preserving pre-existing shadows and typography metrics.
- Applied the contrast scope to Profile Watch Time and Supporter Membership content so their independent surface toggles also control readability.
- Added host-test assertions that every explicit Material3 typography style receives a shadow in the glass scope.

### Verification

- Confirmed the project pins Compose Multiplatform `1.13.0-beta01`, Material3 `1.13.0-beta01` (based on AndroidX Material3 `1.5.0-beta01`), and material-icons-extended `1.7.3`.
- Confirmed the Material3 `Typography.copy` API for standard and emphasized styles, and the documented `Text` / `LocalTextStyle` default behavior.
- No tests, builds, or CI workflows were started, per the current authorization boundary. The test and source changes remain unexecuted and uncompiled.
- No local checkout exists in this runtime, so a local `git status` or local working-tree diff cannot be inspected. All repository edits use a branch-head compare-and-swap against the verified remote head.

### Deferred

- Settings background capture still opts in Profile Watch Time and Supporter Membership regardless of the visible Settings page. Reducing that capture requires a migration-safe way to expose the current saveable page state before the backdrop source is created; moving `rememberSaveable` call sites without validating restoration is not justified here.
- Device rendering, contrast validation on bright custom backgrounds, and Android/iOS behavior remain unverified until a test/build and manual-device pass are authorized.



## Cross-platform Editor and fallback review — 9 November 2026, 23:17 UTC

### Platform capability facts, checked against pinned source

- The app pins Kyant Backdrop 2.0.1, Compose Multiplatform 1.13.0-beta01, Kotlin 2.4.10, Android min SDK 24, and iOS deployment target 16.1.
- Kyant's Android isRenderEffectSupported() is API 31+; isRuntimeShaderSupported() is API 33+. Thus API 24–30 cannot run Backdrop blur or lens, API 31–32 can blur but cannot refract with RuntimeShader, and API 33+ can use both subject to shape and size constraints. This was checked in Kyant's Android Platform.kt and RuntimeShader.kt, plus the Backdrop effects guide.
- Kyant's Skiko implementation reports RenderEffect and RuntimeShader capability as available and uses Skia RuntimeEffect/RuntimeShaderBuilder. This is the shared Compose renderer used on iOS; it is not Apple's native Liquid Glass.
- iOS 26 native availability is checked at runtime. iOS 16.1–25 retains the Compose/Skiko fallback. iOS 26+ uses native UIKit/SwiftUI only at specific bounded controls and system presentation surfaces.

### Actual iOS-native surface coverage rechecked

- Native system navigation/tab presentation is in ContentView.swift; the custom iPad tab bar in NuvioGlassTabBar.swift / NuvioSegmentedTabBar.swift uses UIVisualEffectView(UIGlassEffect()).
- Native bottom sheets use UIKit UIModalPresentationPageSheet on iOS; older iOS and disabled-surface paths retain the configured Compose content background.
- Native control adapters are runtime-gated to iOS 26+: SearchScreen search, Stream filter buttons, Stream selection dropdown/menu, Library source segmented control, Editor enable switches, and Player speed option menu. Android's actual reports native controls unavailable; iOS before 26 does likewise. These paths retain Compose controls on Android and older iOS.
- Player speed's native iOS option-menu path already existed; no duplicate migration was needed.
- The player-video glass migration remains blocked on proof that UIVisualEffectView samples Nuvio's real CAMetalLayer video. No blanket UIKit overlay, private backdrop API, or CPU frame copy was introduced.

### New omissions and corrections

1. Cloud Library Search runtime wiring: cloudSearchGlassEnabled was computed and passed to cloudLibraryContent, but the production CloudLibrarySearchField call ignored it. Studio did apply the per-surface recipe, creating a preview/runtime mismatch. The production field now uses the LIBRARY_CLOUD_SEARCH shape/recipe, transparent text-field container, outline rule, and backdrop-readiness check. On iOS 26 it selects UISearchBar only while this surface is enabled; otherwise Android and older iOS keep the Compose field. The existing animated auto-focus is preserved: Compose uses its FocusRequester, while UIKit receives the existing focus-request counter and calls becomeFirstResponder.
2. The Studio preview now avoids applying the Kyant glass modifier below the native iOS UISearchBar, preventing a stacked/double material. It keeps the Kyant path for Android and older iOS.
3. Android API 24–30 static fallback: when glass is enabled but RenderEffect is unsupported, surfaces now reuse the existing subtle adaptive body tint/sheen/shade over their ordinary fallback fill. This is explicitly a static treatment, not blur or refraction. The global glass switch and per-surface opt-out still bypass this film and keep the previous plain fallback. API 31+ rendering is unchanged.
4. iOS tab-bar hide animation: the iPad auto-hide wrapper previously animated opacity on an ancestor of UIVisualEffectView(UIGlassEffect()). Apple's UIKit documentation warns that alpha below 1 on the effect view or any ancestor can make the effect render incorrectly or disappear. The fade is removed; the existing slide animation is kept, and the hidden bar is also removed from accessibility and hit testing.

### Sources and cross-checks

- Kyant Backdrop Android capability source: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/androidMain/kotlin/com/kyant/backdrop/Platform.kt
- Kyant Backdrop Android RuntimeShader source: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/androidMain/kotlin/com/kyant/backdrop/RuntimeShader.kt
- Kyant Backdrop Skiko capability source: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/skikoMain/kotlin/com/kyant/backdrop/Platform.kt
- Kyant Backdrop effects guide: https://kyant.gitbook.io/backdrop/api/backdrop-effects
- Apple UIKit UIVisualEffectView guidance: https://developer.apple.com/documentation/uikit/uivisualeffectview
- Apple Liquid Glass adoption guidance: https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass

### Verification boundary and next starting point

- Added a pure regression test for the unsupported-renderer static fallback policy; it has not been executed. No build, test, CI, compilation, simulator or device run was started.
- Changes were made against the verified remote branch head through GitHub tree/commit operations. No local checkout is available here; local uncommitted working-tree changes cannot be observed.
- Remaining manual checks: Android API 24–30 static fallback contrast; API 31–32 blur without lens; API 33+ blur/lens; iOS 16.1–25 Compose fallback; iOS 26+ native tab bar/search controls; iPad auto-hide with VoiceOver; Library Cloud search keyboard focus and clear/search behavior.
- The earlier page-aware Settings backdrop-capture optimization remains deferred until rememberSaveable navigation state can be moved safely with restoration regression coverage.


### Cloud search focus lifecycle follow-up — 9 November 2026

- The common UIKit adapter uses both a one-shot focus request count and the current Compose focus state. The Library wrapper now feeds the UISearchBar focus callback back into shouldBeFocused, matching the main Search screen. This prevents a later Compose update from treating the programmatic first-responder request as stale and resigning it.

## Native search focus delivery correction — 9 November 2026

### Findings from the caller and lifecycle trace

- `SearchScreen` increments `searchFocusRequest` for both the toolbar search action and external tab-level focus requests, but the iOS-native field was passed a separate `nativeFocusRequestCount` that was initialized to zero and never incremented. The native search therefore received no programmatic focus request from either entry point; the Compose path continued to use the real request counter.
- The native adapter also called its complete update routine from `UIKitView.factory`. In the app's pinned Compose Multiplatform `1.13.0-beta01` source, the interop view is constructed during `factory`, while its `update` callback is deferred until the holder is inserted into the interop hierarchy. A focus attempt in the factory can therefore happen before the native field belongs to a window, and can be consumed before the attached update gets a chance to handle it.
- The shared adapter serves both Search and Library Cloud Search. The Library path already increments its own one-shot counter after it becomes visible; that caller's counter behavior was retained.

### Changes in this cycle

- Search now passes its existing `searchFocusRequest` directly to the native field instead of maintaining a second, unsynchronized counter.
- The native search factory now only constructs the `UISearchBar`. Initial value, accessibility and focus synchronization are handled by the `UIKitView` update callback after insertion.
- The native adapter consumes a one-shot focus request only when the text field is already first responder or `becomeFirstResponder()` reports success. A rejected request remains pending for a later update.
- No shared API, dependency, backend setting, Android code, older-iOS Compose fallback, or navigation behavior was changed.

### Evidence

- Pinned JetBrains implementation: `UIKitView.ios.kt`, `InteropView.skiko.kt`, and `InteropViewHolder.skiko.kt` at https://github.com/JetBrains/compose-multiplatform-core/tree/v1.13.0-beta01
- Apple responder contract: https://developer.apple.com/documentation/uikit/uiresponder
- Apple keyboard/focus troubleshooting: https://developer.apple.com/library/archive/qa/qa1813/_index.html

### Verification boundary and next check

- Reviewed the relevant call sites and the pinned framework lifecycle source; this is source-level verification only.
- No tests, compilation, Android/iOS builds, simulator, device, or CI workflow were run.
- Manually verify on iOS 26+ that Search opens the native keyboard from both the in-screen search action and an external/tab focus request, and that Library Cloud Search opens the keyboard when revealed. Also verify typing, clear, dismiss/reopen and tab changes; separately confirm iOS 16.1–25 and Android retain their Compose focus path.
