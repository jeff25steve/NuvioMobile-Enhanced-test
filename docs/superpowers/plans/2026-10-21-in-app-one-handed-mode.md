# In-App One-Handed Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an entirely on-device, in-app one-handed presentation toggled by the existing Library press-and-hold gesture without disturbing profile switching, navigation gestures, Liquid Glass, or playback.

**Architecture:** Keep one-handed mode state scoped to the root app composition and apply one shared presentation boundary to ordinary app UI only. Integrate Library hold recognition with the existing floating navigation gesture flow by matching the current profile hold semantics and preserving drag-to-select. Use an ephemeral, one-time in-memory snapshot only if safe and performant; otherwise use a theme-aware frosted-glass fallback. Player entry must synchronously disable the mode and release captured pixels.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, existing Nuvio navigation/player architecture, existing Liquid Glass/backdrop APIs.

**Spec:** `docs/superpowers/specs/2026-10-21-in-app-one-handed-mode-design.md`

## Global Constraints

- **Context7 is mandatory before each code change:** before implementing, adding, changing, or deleting code, resolve the correct Context7 library ID and query current official documentation for the API/library relevant to that edit. Repeat when moving to a materially different API/library or platform surface; do not assume one lookup covers the entire feature.
- **Exa is used when necessary:** use Exa for current external research, upstream repository behavior, platform limitations, issue/discussion corroboration, or when official docs do not resolve an implementation question. Prefer Context7/official documentation for API contracts and Exa for broader corroboration. Do not use Exa as a substitute for inspecting the actual repository code.
- If Context7 or Exa is unavailable or fails, stop before the affected code change, report the blocker, and do not silently replace current-library verification with memory or guesswork.
- All feature processing stays on-device; no feature-related network requests or transmission of UI pixels/state. Developer research tooling is only for researching implementation contracts; the shipped feature must not communicate UI pixels/state or feature telemetry externally.
- No background/foreground service, persistent notification, overlay permission, accessibility service, polling, or background monitoring.
- Normal Library tap continues navigating; Library press-and-hold toggles one-handed mode directly without a popup.
- Existing profile-button press-and-hold popup and behavior remain unchanged.
- Existing drag-to-select behavior and all transformed touch interactions must remain correct.
- Opening any video/trailer disables mode and releases the snapshot before player presentation; playback exit never restores mode automatically.
- No playback state/progress/control changes and no protected player surface capture.
- Snapshot is ephemeral in-memory only, never persisted, logged, or uploaded; release promptly.
- Do not change product source until repository-maintainer approval of the feature request is explicit.
- Do not use GitHub Actions/workflows before the user's previously specified quota-reset date has passed; use local/device checks where available and report limitations honestly.

## Review Focus

- Long-press versus normal tap/drag on Library — test tap navigation, hold toggle, and drag-to-select separately.
- Existing profile hold — regression test profile switching and confirm it never toggles one-handed mode.
- Scaled hit testing and overlays — exercise scrolling, buttons, dialogs, menus, and Liquid Glass interactions.
- Player transition and mini-player ownership — test synchronous disablement, snapshot release, unchanged playback position, and no automatic re-enable.
- Snapshot failure/resource pressure and lifecycle changes — test fallback, rotation/insets, disposal, and no stale backdrop.

---

### Task 1: Trace existing gesture, root layout, and player boundaries

**Files:**
- Inspect: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Inspect: `composeApp/src/commonMain/kotlin/com/nuvio/app/RootTabHost.kt`
- Inspect: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- Inspect: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NavigationBar.kt`
- Inspect: current profile press-and-hold implementation and all video/trailer navigation entry points.
- Inspect: platform-specific capture/rendering APIs and existing tests.

**Interfaces:**
- Produces a verified map of root content, navigation items, profile hold implementation, player routes/hosts, and safe platform-specific capabilities. No product behavior changes in this task.

- [ ] Read the exact profile-button hold code and record its timing, cancellation, and popup semantics.
- [ ] Trace all video/trailer entry points, including mini-player/hosted-player routes.
- [ ] Identify the narrowest shared boundary containing ordinary app content while excluding player surfaces and platform dialogs that cannot participate safely.
- [ ] Identify existing test targets and on-device build/run commands from repository configuration.
- [ ] Confirm maintainer-approved feature-request issue exists before any product-source modification.

### Task 2: Add a small, testable one-handed mode state model

**Files:**
- Create or modify: a focused commonMain UI state/controller file beside existing app-level UI state, path chosen after Task 1.
- Test: matching commonTest or existing unit-test location.

**Interfaces:**
- Define a small state model with enabled/disabled state, horizontal placement (only if supported cleanly), and an explicit disable transition for player entry.
- Keep state UI-scoped and non-persistent for the first version.

- [ ] Write tests for disabled → enabled → disabled, outside-tap dismissal, and repeated toggle behavior.
- [ ] Run the focused test and confirm the expected pre-implementation failure.
- [ ] Implement the minimal state model without service, storage, networking, or background work.
- [ ] Run the focused test and confirm it passes.
- [ ] Commit the state-model change with a focused message.

### Task 3: Integrate Library press-and-hold without breaking the navigation slider

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- Modify the narrow caller/item model that constructs Library and profile items, identified in Task 1.
- Test: gesture/state tests at existing supported test layer.

**Interfaces:**
- Extend `FloatingNavigationItem` or the existing gesture callback contract with a distinct hold action only if the current architecture needs it; preserve current tap callback and item identity.
- Keep `detectJellyTabGestures` as the single coordinated gesture path unless code inspection proves a non-competing existing mechanism is safe.

- [ ] Add failing tests or deterministic gesture/state tests for normal tap, hold, drag, cancellation, and profile-hold independence.
- [ ] Reuse the profile hold's exact threshold and cancellation semantics; Library hold invokes only the one-handed toggle and shows no popup.
- [ ] Ensure a hold is consumed once, does not also invoke Library navigation on release, and a drag still selects the intended tab.
- [ ] Run focused gesture tests and confirm pass.
- [ ] Commit the navigation gesture change.

### Task 4: Apply one-handed presentation to ordinary app content

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/RootTabHost.kt` only if Task 1 confirms the root presentation boundary requires it.
- Create: a focused presentation/backdrop composable if needed, in the nearest existing app UI package.
- Test: state/layout tests in existing test structure.

**Interfaces:**
- One root-owned state/controller feeds a single presentation boundary; do not add independent flags to individual screens.
- Reduced content remains the only interactive UI in its region. Outside-region taps dismiss mode and cannot pass through to hidden controls.

- [ ] Write tests for enabled/disabled layout state, bottom alignment, left/right placement where supported, and dismissal behavior.
- [ ] Implement proportional scaling and placement with correct transformed pointer coordinates; do not rely on visual-only graphics scaling.
- [ ] Verify dialogs, menus, scroll, focus, insets, orientation, and Liquid Glass/backdrop bounds; handle unsupported platform popups deliberately.
- [ ] Add an on-device, one-time snapshot path only if safe and supported by existing platform APIs. Capture once, blur/dim in memory, do not recapture continuously, and promptly dispose.
- [ ] If capture is unsupported, unsafe, too costly, or fails, use theme-aware frosted-glass/gradient fallback without crashing.
- [ ] Run focused tests and manual device checks; commit the presentation change.

### Task 5: Disable mode before video/trailer presentation

**Files:**
- Modify: the verified video/trailer navigation actions and player route/host boundary identified in Task 1.
- Test: existing player/navigation test targets or focused state transition tests.

**Interfaces:**
- Player entry calls the mode controller's explicit disable transition synchronously before player visibility; route detection is a defensive second guard.
- The player remains outside the transformed/captured subtree.

- [ ] Add failing tests for each supported video/trailer entry route and for remaining disabled after playback exit.
- [ ] Implement pre-navigation disablement and snapshot release; retain existing player/mini-player ownership.
- [ ] Verify media selection, playback progress, resume state, controls, orientation, and insets remain unchanged.
- [ ] Run focused tests and manual device playback checks; commit the player-boundary change.

### Task 6: Final privacy, regression, and device verification

**Files:**
- Modify only files justified by a failing verification.
- Add/update tests only where they directly protect the agreed behavior.

- [ ] Verify no new permissions, services, workers, persistent notifications, network calls, analytics, screenshot files, or logs are introduced.
- [ ] Verify snapshot release on mode exit, capture failure, screen disposal, and player entry.
- [ ] Verify Library tap/hold/drag, profile hold, scrolling, dialogs/popups, Liquid Glass, left/right placement, and outside dismissal on device.
- [ ] Verify every video/trailer path disables mode before player presentation and playback exit does not restore it.
- [ ] Run applicable common tests and platform builds using available local tooling; do not invoke GitHub Actions/workflows before the user's stated quota-reset date.
- [ ] Record the exact device, tests, builds, and outcomes; do not claim checks that were not run.
- [ ] Commit any final targeted fixes and perform a whole-branch review against the approved spec.
