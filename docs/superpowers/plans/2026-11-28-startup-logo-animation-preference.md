# Startup Logo Animation Preference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an off-by-default, searchable setting to skip Nuvio's decorative startup logo animation without skipping authentication or profile selection.

**Architecture:** Store a device-local boolean through the existing ThemeSettingsStorage actuals. A pure common policy combines that preference with the platform Reduce Motion state. AppGate removes only the intro hold and AppIntroContent shows a static wordmark when motion is skipped.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.13.0-rc01, existing SharedPreferences / NSUserDefaults storage, Kotlin common tests. No new dependency.

**Spec:** docs/superpowers/specs/2026-11-28-startup-logo-animation-preference-design.md

## Global Constraints

- Work only on main-build-testing; preserve all unrelated work and never force-push.
- Keep the default false for existing and new installs.
- Do not change authentication, profile loading, profile selection, PIN/biometric, or post-selection transitions.
- Keep this preference app-install local; do not profile-scope or sync it.
- Do not modify Android's native splash screen or system accessibility setting.
- Respect Android API 26+ animator-disabled state and iOS Reduce Motion.
- No dependency, permission, hidden API, system setting mutation or background job.
- Do not run builds, tests, compilation, CI, benchmarks or device runs without user authorization.

## Review Focus

- Existing installs without the key remain on the original intro.
- Enabling the app setting skips intro motion but does not bypass profile security or selection.
- System Reduce Motion skips startup motion even when the app toggle is off.
- Existing profile switch/selection transitions are untouched.
- Settings search navigates to the exact Appearance → Display row and unknown keys remain page-only.

---

### Task 1: Define and test the motion policy

**Files:**
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/AppIntroContent.kt
- Create: composeApp/src/commonTest/kotlin/com/nuvio/app/AppStartupMotionTest.kt

**Interfaces:**
- shouldSkipStartupLogoAnimation(skipStartupAnimation: Boolean, platformPrefersReducedMotion: Boolean): Boolean
- shouldWaitForStartupLogoAnimation(skipStartupAnimation: Boolean, platformPrefersReducedMotion: Boolean, animationFinished: Boolean): Boolean
- expect fun platformPrefersReducedMotion(): Boolean

- [ ] Add tests for default-off, explicit opt-in, platform Reduce Motion, and immediate gate release.
- [ ] Implement pure policy; keep platform state behind the expect/actual function.

### Task 2: Add platform accessibility adapters and device-local persistence

**Files:**
- Create: composeApp/src/androidMain/kotlin/com/nuvio/app/AppStartupMotion.android.kt
- Create: composeApp/src/iosMain/kotlin/com/nuvio/app/AppStartupMotion.ios.kt
- Modify: ThemeSettingsRepository.kt, ThemeSettingsStorage.kt, ThemeSettingsStorage.android.kt, ThemeSettingsStorage.ios.kt.

- [ ] Android API 26+ reads ValueAnimator.areAnimatorsEnabled(); API 24–25 keeps existing motion. Do not use hidden settings APIs.
- [ ] iOS reads UIKit's public UIAccessibilityIsReduceMotionEnabled() platform binding.
- [ ] Persist raw, installation-local boolean without ProfileScopedKey; absent value defaults to false.
- [ ] Do not include the key in sync/export payloads or clear it on sign-out.

### Task 3: Wire startup and Settings

**Files:**
- Modify AppGate.kt and AppIntroContent.kt.
- Modify AppearanceSettingsPage.kt, SettingsSearch.kt and values/strings.xml.
- Test SettingsSearchAnchorTest.kt.

- [ ] Add the Appearance → Display toggle and catalogue search terms.
- [ ] In AppGate, initialize intro completion immediately when the app or platform preference skips motion.
- [ ] In AppIntroContent, render the same final wordmark without fade/scale/glow and signal completion; leave the existing animated branch unchanged.
- [ ] Preserve all auth/profile/security flows and normal profile transitions.
- [ ] Test exact Appearance row anchoring and unknown-key fallback.

### Task 4: Documentation and source review

**Files:**
- Create this spec and plan.
- Append a concise note to docs/engineering/codebase-improvement-notes.md.

- [ ] Re-fetch all changed files and review API names, expect/actual pairing, storage default, search anchor, Compose state and security-path diffs.
- [ ] Record that tests were authored but not run; do not claim compilation or device verification.
- [ ] Commit with [skip ci]; use a fast-forward ref update only.
