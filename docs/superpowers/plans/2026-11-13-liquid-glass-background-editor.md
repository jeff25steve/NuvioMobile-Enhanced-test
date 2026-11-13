# Liquid Glass Background Editor Implementation Plan

> **For agentic workers:** Implement the tasks in order with test-first changes. The CI validation copy is isolated on a dated branch; the reviewed product code and tests are integrated into main-build-testing. Keep the CI-only workflow trigger out of the product branch.

**Goal:** Add Settings-page custom background image controls to the Liquid Glass Editor while retaining and unifying the existing Cloud Library controls.

**Architecture:** Resolve editor surfaces to a small target enum and render one shared composable. The composable selects the correct existing profile-scoped StateFlow and save/clear methods, and reuses the existing native image picker. The live screen and editor previews already consume those same persisted URIs and revisions.

**Tech Stack:** Kotlin, Compose Multiplatform 1.13.0-beta01, existing AndroidX Activity Photo Picker, iOS PhotosUI PHPicker, Coil 3.6.3.

**Spec:** docs/superpowers/specs/2026-11-13-liquid-glass-background-editor-design.md

## Global Constraints
- No new dependencies or storage formats.
- Settings and Cloud image keys remain independent and device/profile local.
- Preserve cancellation propagation, local-file URI validation, image size/dimension limits, and preview cache revision behavior.
- Do not change unrelated Liquid Glass surfaces.
- Linux build is allowed only on the dedicated ci/nuvio-main-build-testing-settings-cloud-background-20261113 branch.

## Review Focus
- Settings selection must never write to the Cloud image key.
- Cloud surfaces must continue using the Cloud image key.
- Unrelated surfaces must not show background controls.
- Save/remove failures must remain visible without swallowing coroutine cancellation.
- Existing preview revision changes must refresh the image after save/remove.

---

### Task 1: Target routing and shared editor controls

**Files:**
- Create: composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/LiquidGlassBackgroundImageControls.kt
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/LiquidGlassSurfacesSettingsSheet.kt
- Test: composeApp/src/commonTest/kotlin/com/nuvio/app/features/settings/LiquidGlassBackgroundImageTargetTest.kt

**Interfaces:**
- Produces LiquidGlassBackgroundImageTarget, liquidGlassBackgroundImageTarget(surface), and LiquidGlassBackgroundImageControls(surface).
- Consumes existing ThemeSettingsRepository, ProfileRepository, and rememberSettingsBackgroundImagePicker.

- [x] Write target-routing tests for Settings, all three Cloud surfaces, and unrelated surfaces (added; execution pending).
- [ ] Verify expected routing with the available test workflow when authorised; source-only review is not a passing test.
- [x] Implement the target-aware shared control, preserving the existing localized copy and profile-scoped save/clear calls.
- [x] Replace the duplicated Cloud-only block and enable the Settings target in both editor layout branches.
- [x] Review the diff for independent storage keys and cancellation propagation (source-only review; tests not run).

### Task 2: Isolated Linux validation and notes

**Files:**
- Modify: .github/workflows/pr-full-debug-build.yml on the CI branch only.
- Modify: docs/engineering/codebase-improvement-notes.md.

- [x] Add the dated CI branch to the workflow push trigger, following the previous branch-isolated fullDebug validation pattern.
- [x] Record both improvement cycles, the preserved native picker/storage behavior, and verification limits in the existing notes.
- [x] Push the CI branch and confirm the workflow run was created. APK build/artifact verification remains pending; no new CI run will be started without authorisation.
- [ ] Verify the branch head, changed paths, workflow result, and that main-build-testing remains unchanged.


### Task 3: Integrate the reviewed product code into main-build-testing

**Files:**
- Integrate the shared editor control, editor sheet wiring and routing tests.
- Update the existing engineering notes with the actual product-branch integration.

- [x] Integrate the Kotlin implementation and tests into main-build-testing.
- [x] Keep the CI-only workflow trigger out of main-build-testing.
- [x] Verify the remote branch head and tree after integration.
- [x] Confirm the push does not match any configured automatic build/CI trigger.
- [x] Leave builds and tests unrun in this integration cycle, per the latest authorisation boundary.
