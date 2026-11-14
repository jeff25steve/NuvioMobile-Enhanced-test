# Sentry Android startup performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Reduce Sentry's contribution to Android launch time by updating its SDK and build plugin to versions containing verified startup optimizations.

**Architecture:** Keep Sentry initialization at its current early lifecycle point to preserve startup crash capture. Upgrade the existing Android SDK and Gradle plugin, and explicitly enable the plugin's build-time runtime optimizations without changing other Sentry behavior.

**Tech Stack:** Sentry Android SDK 8.64.0, Sentry Android Gradle Plugin 6.27.0, Gradle version catalog, Android Gradle Plugin 9.4.0.

**Spec:** `docs/superpowers/specs/2026-11-14-sentry-startup-performance-design.md`

## Global Constraints
- Preserve Sentry's existing DSN, privacy and filtering configuration.
- Keep `autoInstallation.enabled=false` and `tracingInstrumentation.enabled=false`.
- No Gradle builds, compilation-backed tests, Android/iOS builds or CI runs without authorization.
- Do not claim measured Nuvio launch-time improvement without Macrobenchmark/device data.

## Review Focus
- Dependency resolution: ensure Sentry Android SDK and plugin versions are aligned with the verified releases.
- Plugin configuration: runtime optimizations enabled, while tracing instrumentation and auto-installation remain disabled.
- Privacy: no change to PII, screenshot, view hierarchy, request/user filtering, breadcrumbs or telemetry settings.
- Startup reliability: keep initialization timing unchanged to retain early crash reporting.
- Platform scope: only Android's existing Sentry dependency and plugin change; no iOS or shared runtime behavior changes.

---

### Task 1: Upgrade Sentry Android SDK

**Files:**
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: existing `libs.sentry.android` alias.
- Produces: SDK version `8.64.0` used by the existing Android dependency.

- [ ] Change `sentry = "8.47.0"` to `sentry = "8.64.0"`.
- [ ] Verify the exact `getsentry/sentry-java` release tag `8.64.0` and inspect its startup-related fixes.
- [ ] Commit the SDK version change.

### Task 2: Upgrade Sentry Gradle plugin and enable build-time optimizations

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `androidApp/build.gradle.kts`

**Interfaces:**
- Consumes: plugin alias `libs.plugins.sentry.android.gradle`.
- Produces: plugin version `6.27.0` with `runtimeOptimizations.enabled=true`.

- [ ] Change `sentryGradle = "6.14.0"` to `sentryGradle = "6.27.0"`.
- [ ] Add `runtimeOptimizations { enabled.set(true) }` to the existing Sentry configuration.
- [ ] Preserve auto-installation and tracing instrumentation as disabled.
- [ ] Verify the exact plugin release and documented defaults/DSL.
- [ ] Commit the plugin/configuration change.

### Task 3: Update engineering notes and remote verification

**Files:**
- Modify: `docs/engineering/android-startup-critical-path-review.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`

- [ ] Record evidence, exact version changes, expected impact and limitations.
- [ ] Explicitly state that no build, test, benchmark, device run or CI was executed.
- [ ] Fetch changed files and branch head from GitHub; verify no unrelated or workflow changes.
- [ ] Report that build/binary compatibility remains unverified pending user authorization.
