# Navigation Liquid Glass Controls Implementation Plan

> **For agentic workers:** Use the Superpowers execution workflow task-by-task. Follow the spec and keep the diff focused.

**Goal:** Fix the doubled/tinted active navigation row and add independently adjustable, theme-aware Liquid Glass controls that save when the Navigation Bar settings sheet closes.

**Architecture:** Use one common validated settings model for the floating capsule and one for the selected-tab lens. Keep live values in the existing `ThemeSettingsRepository` StateFlows, persist serialized values through the existing profile-scoped `ThemeSettingsStorage` on sheet exit, and add both settings to existing sync payloads. Parameterize the existing Kyant `drawBackdrop` effects and keep regular Nuvio icons, labels, gestures, glow, and non-Liquid-Glass fallback unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Material 3, Kyant0/AndroidLiquidGlass KMP `backdrop` 2.0.1, existing Android host tests/Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-13-navigation-liquid-glass-controls-design.md`

## Global Constraints

- Work only on `feature/navigation-liquid-glass-clean-port-20261018`; do not overwrite or rebase existing branch work.
- Keep the dependency surface minimal; the one new dependency is the explicitly scoped Kyant `io.github.kyant0:backdrop:2.0.1` library required for this feature. Do not add any other dependency.
- Preserve existing navigation routes, order, icons, labels, RTL mapping, gestures, style/position/glow settings, native iOS bar, and regular frosted fallback.
- New saved configuration remains profile-scoped and round-trips through settings sync.
- Liquid Glass effect controls are editable only while the Liquid Glass material is enabled and supported. The separate default inactive-icon-colour setting remains available for non-Classic floating navigation even when Liquid Glass is off.
- The two reset icon buttons affect separate settings groups; changes are saved on every sheet exit path, with no separate Save action.
- Do not start GitHub Actions while the account quota is exhausted. Do not report a build/test/device pass without fresh verification evidence.

## Review Focus

1. **Overlay rendering:** hidden/reference tab content must remain a backdrop source, not paint a second set of visible labels/icons.
2. **Effect bounds:** malformed, non-finite, negative, or out-of-range values resolve to valid settings; zero blur/refraction safely disables that effect.
3. **Exit lifecycle:** Done, system Back, swipe dismissal, and outside tap all persist both effect groups before removing the sheet.
4. **Profile isolation/sync:** saved effects round-trip through sync without removing another profile's preferences; old payloads use defaults.
5. **Rendering compatibility:** Android below API 33 retains its current fallback; iOS/common call sites keep compiling with the new common settings model.

---

### Task 1: Model defaults and safe value validation

**Files:**
- Create `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/glass/LiquidGlassEffectSettings.kt`
- Create `composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/ui/glass/LiquidGlassEffectSettingsTest.kt`

**Interfaces:**
- `LiquidGlassEffectSettings` contains corner-radius percent, blur dp, refraction-height dp, refraction-amount dp, chromatic-aberration, depth-effect and tint opacity.
- Separate `FloatingBarDefaults` and `SelectionLensDefaults` define the documented values.
- `sanitized()` clamps finite numeric values to their documented ranges and handles non-finite values safely.

- [x] Add tests for defaults, both ends of each numeric range, non-finite and out-of-range inputs, and the distinct chromatic-aberration defaults.
- [ ] Run the new tests through the repository's native test environment. Test source and expectations were reviewed, but the build environment was unavailable in this session.
- [x] Implement the model and keep UI-specific conversions (dp-to-px, rounded-corner percent) at the rendering boundary.

### Task 2: Persist profile-scoped settings and sync

**Files:**
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsStorage.kt`
- Modify `composeApp/src/androidMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsStorage.android.kt`
- Modify `composeApp/src/iosMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsStorage.ios.kt`
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsRepository.kt`
- Modify `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/settings/ThemeSettingsStorageTest.kt`

**Interfaces:**
- Store each effect group as a version-tolerant serialized string under an independent profile-scoped key.
- Common storage exposes load/save strings for floating-bar and selection-lens settings.
- Repository exposes live read-only StateFlows and draft update/commit functions. Update functions only change in-memory values; commit writes both current groups at sheet exit.
- Sync payloads export/import each configuration under independent keys; malformed values are parsed safely and sanitized to defaults/valid ranges.

- [x] Add tests for storage reload, sync round-trip, missing keys, malformed serialized values and profile-key isolation.
- [x] Implement common storage contracts and both platform storage functions with the existing ProfileScopedKey pattern.
- [x] Add repository load/reset/update/commit behavior and ensure `clearLocalState`/profile reload reset or reload the new flows.

### Task 3: Parameterize the glass render surfaces and correct overlay

**Files:**
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/glass/GlassBarSurface.kt`
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt`

**Interfaces:**
- Pass floating bar and selection lens settings to their respective surfaces; keep the defaults explicit and independent.
- Convert dp effect values to pixels with the active draw density before passing them to Kyant `blur`/`lens`.
- Clamp/sanitize before use. Zero blur omits the blur effect; zero height or amount omits the lens; toggles choose the appropriate upstream Boolean parameters. Keep the configured selected lens active at rest so its settings sliders visibly tune the material; retain touch-driven highlights/shadows and jelly deformation.
- Use the selected surface's configurable shape in both its backdrop and clip.
- Render selected-tab content only inside the moving selected pill. Preserve the existing selected-tab accent styling inside that pill, keep inactive icons/labels muted, and ensure slider edits never alter navigation icon/label colours; preserve regular non-Liquid-Glass styling.
- Preserve Android < API 33 fallback and shared/iOS compile compatibility.

- [x] Keep the LTR/RTL slot-offset helper independent of `panelOffset`: source brace nesting confirms the selected lens is inside the same translated parent `Box` as the visible row, so the parent applies `panelOffset` to both exactly once. Actual rendered path geometry remains unverified.
- [x] Implement the minimum rendering changes; retain Kyant's actual API and existing JellyMotion.
- [x] Review RTL, selected index bounds, and all call sites by source inspection; runtime behavior remains unverified.

### Task 4: Add accessible live controls and independent reset buttons

**Files:**
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/NavigationBarSettingsSheet.kt`
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/NavigationBarPreview.kt`
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppearanceSettingsPage.kt`
- Modify `composeApp/src/commonMain/composeResources/values/strings.xml`

**Behavior:**
- Show two groups only when Liquid Glass is enabled and supported: Floating bar and Selected-tab lens.
- Each group gets Material 3 sliders for corner radius, blur, refraction height/amount and tint opacity, plus switches for chromatic aberration and depth effect.
- Standard sliders inherit `MaterialTheme.colorScheme.primary`; do not use the custom-theme colour-picker's gradient tracks.
- Each heading has an accessible reset icon. Reset changes only its group to documented constants.
- Live flow changes update the preview and running bar; no disk write occurs during interaction.
- Every dismissal path (Done, Back, swipe, outside tap) calls repository commit before dismissing. No separate Save button.
- Preview receives the exact same two settings objects as the production component.

- [x] Add concise labels/content descriptions for the controls and reset buttons.
- [x] Wire draft updates without per-frame persistence and commit both groups through a single exit callback.
- [x] Verify Done and modal dismissal paths use the same save-before-dismiss callback by source inspection.

### Task 5: Preserve the existing navigation icon-colour setting

**Files:**
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt`, `core/ui/FloatingNavigationBar.kt`, `core/ui/jelly/JellyTabs.kt`, and `features/profiles/ProfileSwitcherTab.kt`.
- Modify `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/NavigationBarSettingsSheet.kt`, `AppearanceSettingsPage.kt`, and `NavigationBarPreview.kt`.
- Modify `ThemeSettingsRepository.kt`, common/Android/iOS `ThemeSettingsStorage`, `ProfileSettingsSync.kt`, and the English fallback resources.
- Add/update repository, storage/sync and profile-avatar regression tests.

**Behavior:**
- Keep the setting disabled by default with the existing muted-grey value `#969CA3`; override only inactive floating-navigation icons/labels and the inactive profile-switcher avatar border.
- Keep it independent from Liquid Glass effects and the selected-tab accent. Reuse the existing theme colour picker and validated hex parser.
- Keep the enable switch on the existing immediate-persistence path; treat colour changes as a draft and save them once when the settings sheet exits.
- Keep the value profile-scoped and included in existing sync payloads. Observe the committed colour revision, not each draft frame.
- Do not affect the native iOS tab bar or unrelated theme colours.

- [x] Restore the setting and preview wiring on the clean-port branch.
- [x] Restore profile-scoped Android/iOS storage and committed-colour sync observation.
- [x] Add regression-test sources for repository draft/commit, profile sync/profile isolation, and profile avatar border resolution.
- [ ] Run focused tests and platform builds; not run in this connector-only session.

### Task 6: Final regression and verification pass

**Files:**
- Review all files changed by Tasks 1–4 and any directly needed test changes.

- [x] Inspect the complete change set against the spec and the initial implementation head.
- [ ] Run focused tests and builds. Not run: no working local repository/build environment was available; GitHub commit statuses and associated PR workflow runs are empty. Do not treat this as a pass.
- [x] Review the generated diff: only the scoped navigation, settings/storage, resources, tests, and design/plan files changed relative to the implementation start; theme/RTL/tablet/iOS fallback and sync call paths were source-checked.
- [x] Review claimed passes against tool output. Android and iOS device rendering remain unverified.


## Follow-up review findings (2026-10-13 and 2026-10-15)

- Corrected icon-source composition: clip inactive icons out of the selected pill before recording active selected content, and do not draw a crisp selected row over its refracted copy. The primary lens always samples the screen and adds the floating-bar surface only when that toggle is on. Tab icons now use a separate refraction-only overlay so the selection-lens blur cannot smear the icons; optional icon/bar sources are allocated only when refraction height and amount are both nonzero. Regression tests cover independent source toggles, zero-effect fallback and blur-only behavior.
- Ported the important source-layer distinction from Kyant's KMP `LiquidBottomTabs.kt`: the backdrop sources are kept separate from the selected lens effect stack. The icon source is refracted in its own pass without applying the lens blur or duplicating the lens tint/highlight. The floating-bar source is captured from the rendered child using `layerBackdrop`, including its rounded `drawBackdrop` surface and rim; it never replaces the screen backdrop and excludes Nuvio's separate glow overlay.
- Re-traced the complete `FloatingNavigationBar.kt` brace nesting after the initial offset diagnosis. The selected lens is a sibling of the visible-row child, but both remain inside the enclosing `Box` whose `graphicsLayer` applies `motion.frame.panelOffset`; therefore the helper must not add the offset again. Commit `720d972` removes the temporary explicit extra offset, and `1031607` restores the matching LTR/RTL no-double-offset assertion.
- Moved dismissal-time persistence before suspend/animation work so the values are written synchronously when Done/Back/swipe/outside dismissal begins.
- Changed glass surface tint to use Nuvio's own surface tokens rather than the operating system's light/dark setting.
- Fixed ThemeSettingsRepository visibility so internal glass settings types are not exposed through public APIs.
- Added a commit revision StateFlow to trigger profile-settings sync after both effect groups are saved. Toggle setters persist before emitting their sync-observed StateFlows.
- Removed per-frame glass draft flows from ProfileSettingsSync's full-blob signature observers; only the post-commit revision signals a saved effect change. Isolated slider flows inside NavigationBarSettingsSheetHost to avoid recomposing the whole Appearance section.
- Android/iOS storage and the sync observer were source-inspected. Unit tests were extended for draft-only state, commit revision and profile-key isolation. They were not run: this session has no local repository/build environment, and no GitHub status/workflow run was available. Real-device UI rendering remains outstanding.
- Navigation-settings UX follow-up (2026-10-16): moved the title and interactive navigation preview into an opaque `LazyColumn.stickyHeader`, so settings scroll beneath it without text showing through. A `LazyListState.canScrollBackward` transition gate emits one bounce on leaving the top and one on returning to the top; repeated updates and tab touches do not trigger it. The outline alone animates in the draw phase, leaving preview content and tab touch/JellyMotion behavior unchanged. A focused gate regression test was added; Android/iOS build and visual verification remain unrun in this connector-only session.
- Idle playback/rendering follow-up (2026-10-16): `MainTabsDestination` already gates `FloatingNavigationBar` on the current root route being `TabsRoute`; `PlayerRoute` and detail/trailer routes therefore do not compose the tabs bar. After confirming Kyant Backdrop 2.0.1's `rememberLayerBackdrop` allocates a `GraphicsLayer` and capture only occurs when `layerBackdrop` is attached, the owning allocations were tightened: the main-tabs source now exists only while the custom floating bar is active and Liquid Glass is enabled/supported; the standalone settings-route source exists only while that floating bar is visible with Liquid Glass enabled/supported; the settings preview source exists only when its glass preview is enabled and shader support exists. Haze state is retained only where its non-Liquid-Glass floating-bar fallback needs it. The existing navigation motion loop is inside the bar's composition and is cancelled when that composable leaves composition. Build/device verification remains outstanding.

- Navigation icon-colour integration (2026-10-18): the existing optional default inactive-icon colour setting is included alongside Liquid Glass, including the settings toggle/editor and live preview, muted-grey default (`#969CA3`), profile-switcher inactive border, profile-scoped Android/iOS storage, committed-colour sync revision, English fallback labels and repository/storage/profile-avatar regression-test sources. The colour remains independent from glass-effect sliders, and the pre-existing glow toggle is retained. Source-level checks found consistent call sites, platform storage declarations, sync observer and unique resource names; Android/iOS tests and builds have **not** been run.
