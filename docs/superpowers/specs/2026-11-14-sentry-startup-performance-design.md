# Sentry Android startup performance — design

Date: 14 November 2026
Branch: main-build-testing

## Intent
Reduce Android startup work caused by Sentry initialization while preserving the app's existing crash-reporting configuration and privacy behavior.

## Evidence and current state
- The app pins Sentry Android SDK 8.47.0 and Sentry Android Gradle Plugin 6.14.0.
- `MainActivity.onCreate` calls `SentryInitializer.start(application)` before `super.onCreate`; when Sentry is enabled and a DSN is configured, this reaches `SentryAndroid.init(application)` on the startup critical path.
- Sentry's 20 October 2026 engineering report measures 20–30% faster SDK initialization comparing 8.43.0 with 8.54.0 and recommends SDK >=8.54.0 plus Gradle plugin >=6.20.0. This is Sentry SDK initialization time, not a guaranteed equivalent reduction in Nuvio's total app launch time.
- Sentry 8.64.0 (11 November 2026) adds fixes preventing app-start/foreground main-thread stalls while network/telephony listeners register, and avoids blocking while the frame-metrics thread starts.
- Sentry Android Gradle Plugin 6.27.0 (13 November 2026) is the latest release observed. The plugin's runtime optimizations can resolve manifest metadata and optional class availability at build time.

## Goals
- Upgrade to the current verified Sentry Android SDK 8.64.0 and plugin 6.27.0.
- Explicitly keep Sentry runtime optimizations enabled so generated build-time metadata can avoid runtime manifest parsing/reflection where supported.
- Preserve existing DSN, privacy, filtering, disabled auto-instrumentation, mapping-upload, and auto-installation settings.
- Keep all changes Android-only through existing Android dependencies and plugin configuration.

## Non-goals and anti-goals
- Do not defer Sentry initialization or move it to a worker thread; early crash coverage and Android SDK main-thread requirements must not be weakened.
- Do not change Sentry sampling, PII, screenshots, breadcrumbs, network capture, tracing, or release behavior.
- Do not add dependencies beyond updating the existing Sentry versions.
- Do not run Gradle builds, tests that invoke compilation, Android/iOS builds, or CI without separate authorization.

## Approaches considered
1. Upgrade SDK and plugin in place, retaining the current initialization timing and settings. Chosen: this receives Sentry's measured initialization improvements and later main-thread-stall fixes without sacrificing early crash capture.
2. Defer Sentry initialization until after the first Compose frame. Rejected: it would create a window where startup crashes are not captured and the first Sentry init could contend with first-screen rendering.
3. Move initialization to a background thread. Rejected: Sentry documents main-thread-sensitive integrations; asynchronous initialization can miss early startup crashes and complicate integration correctness.

## Constraints and implementation calls
- Existing versions are declared in `gradle/libs.versions.toml`.
- The app uses `sentry-android` directly and disables plugin auto-installation, so the plugin update must not enable auto-installation.
- Keep `tracingInstrumentation.enabled=false`.
- Explicitly set `runtimeOptimizations.enabled=true` using the plugin's documented DSL.
- Context7 was attempted for the exact SDK/plugin documentation but its monthly quota was exhausted. Use official Sentry release notes, Sentry's engineering report, plugin source and Maven Central as authoritative alternatives; disclose the limitation.
- No build or CI validation is authorized in this task. Verify exact release tags and inspect the source diff remotely; mark binary compatibility/build verification as pending.
