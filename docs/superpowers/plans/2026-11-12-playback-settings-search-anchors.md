# Playback Settings Search Anchors Implementation Plan

> **For agentic workers:** Execute inline with the GitHub repository integration. Steps use checkbox syntax.

**Goal:** Make high-value Playback Player settings searchable and navigate directly to their real controls on phone and tablet.

**Architecture:** Extend the typed anchor registry for SettingsPage.PlaybackPlayer; add catalogue rows using existing localized resources and current visibility conditions; use Foundation BringIntoViewRequester on shared setting rows so nested content inside the single LazyColumn item can reveal itself without layout restructuring.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01, Compose Foundation, existing Settings search catalogue and common tests.

**Spec:** docs/superpowers/specs/2026-11-12-playback-settings-search-anchors-design.md

## Global Constraints

- Do not change any player setting value or playback behavior.
- Do not add dependencies or restructure Playback page layout.
- Keep catalogue visibility aligned with platform and current external-player state.
- Keep unmapped results page-only.
- No builds, tests, compilation, device sessions, or CI without user authorization.
- Commit with [skip ci]; never force-push.

## Review Focus

- iOS-only external-player app row appears only when its UI row is visible.
- Android external-player forwarding rows appear only when external playback is enabled.
- Android decoder rows remain discoverable while disabled by external-player mode, matching their still-visible UI.
- The single-item LazyColumn page reveals a nested row through the scroll parent, not a guessed child item index.
- Unknown Playback keys and all other pages retain page-only navigation.

### Task 1: Regression coverage

**Files:** composeApp/src/commonTest/kotlin/com/nuvio/app/features/settings/SettingsSearchAnchorTest.kt

- [x] Added tests for all six exact Playback Player keys, unknown-key fallback, and phone/tablet offsets.
- [ ] Tests not executed (no Gradle/test tasks authorized).

### Task 2: Search catalogue and typed anchors

**Files:** composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt

- [x] Added strict PlaybackPlayer key allow-list and section item index zero.
- [x] Added localized Player preference and platform/state-conditional external player rows.
- [x] Added Android engine and auto-fallback rows, preserving disabled-but-visible behavior.

### Task 3: Reveal the actual nested row

**Files:** composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsComponents.kt, composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackSettingsPage.kt, composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackPlayerSettings.kt, composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt

- [x] Added shared row-level BringIntoViewRequester behavior.
- [x] Passed highlight state through both phone and tablet page paths.
- [x] Attached exact keys to the actual six Player controls.

### Task 4: Notes and remote integration

**Files:** docs/superpowers/plans/2026-11-12-playback-settings-search-anchors.md, docs/superpowers/specs/2026-11-12-playback-settings-search-anchors-design.md, docs/engineering/codebase-improvement-notes.md

- [x] Recorded API research, visibility conditions, implementation and verification boundary.
- [ ] Runtime/test verification remains pending authorization.
- [x] Source-reviewed remote diff and commit with [skip ci].