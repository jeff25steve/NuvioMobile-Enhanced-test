# Playback Stream Selection Search Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this implementation. Steps use checkbox syntax.

**Goal:** Make all eight Stream Selection controls searchable and directly reachable.

**Architecture:** Extend the strict anchor registry, build entries from the same live state/feature flags as the UI, and reuse row-level bring-into-view/highlight behavior. Preserve actual subsection breadcrumbs.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, Compose Foundation, existing Settings Search catalogue and common tests.

**Spec:** docs/superpowers/specs/2026-11-12-playback-stream-selection-search-design.md

## Global Constraints

- Do not change setting values, playback behavior, dialogs or provider selection.
- Do not add dependencies or restructure the page.
- Catalogue visibility must match actual UI visibility.
- Preserve phone/tablet navigation and page-only fallback.
- No Gradle tasks, tests, builds, compilation, device sessions or CI without authorization.
- Commit with [skip ci]; never force-push.

## Review Focus

- Cache duration is indexed only when Reuse Last Link is enabled.
- Regex Pattern is indexed only for REGEX_MATCH.
- Allowed Addons is absent for ENABLED_PLUGINS_ONLY.
- Allowed Plugins requires pluginsEnabled and source scope other than INSTALLED_ADDONS_ONLY.
- Timeout uses the custom anchor container and retains slider gestures.
- Playback results keep their true subsection breadcrumb.

### Task 1: Regression source

**Files:** composeApp/src/commonTest/kotlin/com/nuvio/app/features/settings/SettingsSearchAnchorTest.kt

- [x] Add tests for all eight exact Stream Selection anchors.
- [x] Cover phone/tablet containing-item offsets and unknown-key fallback.
- [ ] Tests remain unexecuted because test/build tasks are not authorized.

### Task 2: Search catalogue and anchor registry

**Files:** composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt

- [x] Add Stream Selection to strict anchor allow-list with section index zero.
- [x] Add localized entries and aliases for all eight controls.
- [x] Match conditional visibility to PlayerSettingsUiState and pluginsEnabled.
- [x] Preserve actual subsection breadcrumb passed to addRow.

### Task 3: Actual row reveal

**Files:** composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsComponents.kt, composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackStreamSettings.kt, composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackSettingsPage.kt

- [x] Add generic SettingsSearchAnchorContainer for custom slider rows.
- [x] Attach exact keys/highlight to all eight actual controls.
- [x] Pass highlight through PlaybackStreamSelection rendering.

### Task 4: Notes, research and integration

**Files:** docs/superpowers/plans/2026-11-12-playback-stream-selection-search.md, docs/superpowers/specs/2026-11-12-playback-stream-selection-search-design.md, docs/engineering/codebase-improvement-notes.md

- [x] Record API research, visibility rules, implementation and verification boundary.
- [ ] Runtime/test verification pending authorization.
- [x] Review source diff and integrate with [skip ci].
