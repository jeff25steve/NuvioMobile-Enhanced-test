# Android Baseline Profiles for Nuvio — Design

**Date:** 14 November 2026  
**Target branch:** `main-build-testing`  
**Status:** Approved engineering direction; source integration is intentionally separated from profile generation and performance acceptance.

## Problem

The Android app already packages AndroidX ProfileInstaller 1.4.1, but its app-owned `androidApp/src/main/baseline-prof.txt` is empty. There is no Baseline Profile producer module or profile collection journey. ProfileInstaller can install a profile that ships with the app; it does not generate Nuvio's profile rules on its own.

This leaves ART without Nuvio-specific startup rules to precompile on a fresh install or update. Android's guidance says Baseline Profiles can improve code execution from first launch and recommends startup profiles for DEX layout optimization as well as Baseline Profiles for runtime precompilation. These are platform-level potential benefits, not a measured Nuvio result.

## Goals

- Establish a reproducible, manual Baseline Profile generation path for both Android distribution variants.
- Capture only Nuvio's real initial launch path as a Startup Profile, avoiding guesses about login, remote catalog, or feature navigation.
- Preserve the current debug/release behavior, app IDs, Sentry options, permissions, and multiplatform source.
- Keep profile generation out of normal release builds and CI unless separately authorized.
- Generate profile rules from an actual instrumented app run; never hand-author or fabricate ART rules.
- Make profile artifacts reviewable and version-controlled once generated.

## Non-goals

- Do not claim or guarantee a particular Nuvio startup speedup.
- Do not run Gradle, Android Studio builds, CI, Macrobenchmark, or device tests during this source-only integration.
- Do not change app startup initialization, navigation, networking, downloads, or Compose UI.
- Do not capture non-startup user journeys into the Startup Profile.
- Do not make release assembly launch a generator emulator or instrumentation test.

## Evidence and toolchain choice

- Android's current Baseline Profiles guidance lists AGP 8.0+, Macrobenchmark 1.5.0, and ProfileInstaller 1.4.1 as its minimum recommended stable toolchain. Nuvio already uses ProfileInstaller 1.4.1.
- AndroidX Benchmark 1.5.0 is the current stable Macrobenchmark release. The 1.5.0 release line adds support for AGP 9's new DSL; the generator is configured with the stable 1.5.0 release rather than an alpha.
- The repository currently uses AGP 9.4.0 and Gradle 9.8.2. Android's AGP 9.4 release notes require Gradle 9.6.0 or later, which the repository wrapper satisfies.
- The app has `full` and `playstore` product flavors under the `distribution` dimension. The producer module mirrors that dimension and flavor names so the profile build variants match the target app.
- The app's release variant enables R8/minification by default. Android's generation guidance requires capturing from an unobfuscated, non-minified profile-generation variant; the Baseline Profile Gradle plugin supplies the `nonMinifiedRelease` variant and lets R8 rewrite profile rules for the minified release output.

Context7 resolution for the exact AndroidX Baseline Profile plugin documentation was attempted, but the service reported its monthly quota was exhausted. This design therefore relies on Android Developers' official documentation, AndroidX release notes/source, and the existing AGP/Gradle configuration. The exact Nuvio Gradle integration remains uncompiled until an authorized build.

## Options considered

1. **Handwritten rules in `baseline-prof.txt` — rejected.** Rules must represent real, hot code paths; guessing class and method rules can precompile irrelevant code and increase startup I/O.
2. **Automatic generation on every release build — rejected.** Android documents that this starts additional build and instrumentation work and increases build time. It would also violate the user's preference to keep release/CI behavior predictable.
3. **Stable Baseline Profile plugin plus an explicit generator module — selected.** This provides a supported mechanism to collect real rules when authorized, keeps the first integration isolated from app runtime code, and does not trigger profile generation on ordinary release builds.

## Design

### Consumer app module

- Apply the stable `androidx.baselineprofile` plugin to `:androidApp`.
- Add the `:baselineProfile` producer to the app's `baselineProfile(...)` configuration.
- Store generated profiles in source control.
- Keep generation variant-specific (`fullRelease` and `playstoreRelease`) rather than silently merging potentially different distribution artifacts.
- Explicitly leave `automaticGenerationDuringBuild` disabled.
- Keep the existing ProfileInstaller dependency and empty legacy source file until a real generated profile has been produced and packaging has been reviewed.

### Producer module

- Add an isolated `:baselineProfile` Android test module using the AndroidX Baseline Profile Gradle plugin and Macrobenchmark 1.5.0.
- Set `targetProjectPath = ":androidApp"`.
- Mirror the app's `distribution` flavor dimension and `full` / `playstore` flavors.
- Use connected devices for generation by default. Android requires a rooted device/emulator or API 33+ for profile collection; actual generation should use a controlled AOSP emulator or supported physical device.
- Avoid fixed emulator model/API choices in project configuration so that no environment is provisioned automatically and no CI device dependency is introduced.

### Startup journey

- Add one focused instrumentation test for the launch path.
- Target the release package ID `com.nuvio.media`; the app changes only its debug variant's application ID.
- Use `startActivityAndWait()` to exercise the real launcher activity and include this journey in the Startup Profile.
- Do not add fake login state, hard-coded navigation, waits for external services, or assumptions about the first-run onboarding state.
- Extend the profile later with separate non-startup journeys only after those flows have stable, testable setup and explicit value.

## Acceptance criteria

1. Both producer variants map to the corresponding app variants without changing existing user-facing app IDs.
2. Release profile capture uses the plugin's non-minified generation variant, while normal release output retains existing R8 settings.
3. Normal release assemble does not automatically run profile generation or instrumentation tests.
4. Generated `baseline-prof.txt` and `startup-prof.txt` files come from a real instrumented collection run and are committed only after review.
5. A physical-device Macrobenchmark compares cold-start TTID/TTFD with and without the shipped profile using the same device, app version/configuration, and repeated runs. No speed claim is made before this comparison.
6. No profile rule set is accepted if the resulting binary profile exceeds Android's 1.5 MB limit or if the measured startup results regress.

## Rollback

If authorized build verification reveals a plugin, flavor, variant, or profile packaging incompatibility, revert the Baseline Profile plugin aliases, app integration, producer module, and module include together. Leave existing app startup code and ProfileInstaller dependency untouched. Do not suppress plugin compatibility warnings to force an unsupported integration through.

## References

- Android Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
- Android Baseline Profile generation: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Baseline Profile plugin configuration: https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- AndroidX Benchmark release notes: https://developer.android.com/jetpack/androidx/releases/benchmark
- BaselineProfileRule API: https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule
- AGP 9.4.0 release notes: https://developer.android.com/build/releases/agp-9-4-0-release-notes
