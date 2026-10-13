# Navigation Liquid Glass Controls Implementation Plan

> **For agentic workers:** Use the Superpowers execution workflow task-by-task. Follow the spec and keep the diff focused.

**Goal:** Fix the doubled/tinted active navigation row and add independently adjustable, theme-aware Liquid Glass controls that save when the Navigation Bar settings sheet closes.

**Architecture:** Use one common validated settings model for the floating capsule and one for the selected-tab lens. Keep live values in the existing `ThemeSettingsRepository` StateFlows, persist serialized values through the existing profile-scoped `ThemeSettingsStorage` on sheet exit, and add both settings to existing sync payloads. Parameterize the existing Kyant `drawBackdrop` effects and keep regular Nuvio icons, labels, gestures, glow, and non-Liquid-Glass fallback unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Material 3, Kyant0/AndroidLiquidGlass KMP `backdrop` 2.0.1, existing Android host tests/Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-13-navigation-liquid-glass-controls-design.md`

## Global Constraints

- Work only on `feature/navigation-liquid-glass`; do not overwrite or rebase existing branch work.
- Add no new dependency.
- Preserve existing navigation routes, order, icons, labels, RTL mapping, gestures, style/position/glow settings, native iOS bar, and regular frosted fallback.
- New saved configuration remains profile-scoped and round-trips through settings sync.
- Controls are editable only while the Liquid Glass material is enabled.
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

- [ ] Add tests for defaults, both ends of each numeric range, non-finite and out-of-range inputs, and the distinct chromatic-aberration defaults.
- [ ] Verify the new tests' expected behavior by inspection; run through the repository's native test environment if available.
- [ ] Implement the model and keep UI-specific conversions (dp-to-px, rounded-corner percent) at the rendering boundary.

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

- [ ] Add failing tests for storage reload, sync round-trip, missing keys, malformed serialized values and profile-key isolation.
- [ ] Implement common storage contracts and both platform storage functions with the existing ProfileScopedKey pattern.
- [ ] Add repository load/reset/update/commit behavior and ensure `clearLocalState`/profile reload reset or reload the new flows.

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

- [ ] Add or extend a test for selected-index mapping/path boundaries if the existing host-test APIs support it.
- [ ] Implement the minimum rendering changes; retain Kyant's actual API and existing JellyMotion.
- [ ] Review RTL, selected index bounds, and all call sites.

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

- [ ] Add concise labels/content descriptions for the controls and reset buttons.
- [ ] Wire draft updates without per-frame persistence and commit both groups through a single exit callback.
- [ ] Verify all exit paths use the same dismissal callback.

### Task 5: Final regression and verification pass

**Files:**
- Review all files changed by Tasks 1–4 and any directly needed test changes.

- [ ] Inspect the complete change set against the spec and the initial branch head.
- [ ] Run available focused tests, static checks, and builds; if the only available CI is quota-blocked, state that explicitly.
- [ ] Verify the generated diff touches no unrelated files/settings and preserves theme, RTL, tablet layout, iOS fallback, existing bar settings and sync semantics.
- [ ] Review every claimed pass against actual tool output; list Android/iOS device-rendering status explicitly.
