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
- Keep the existing ProfileInstaller dependency and legacy shared source profile unchanged; add separately generated flavor-specific source profiles and only retain them after packaging and same-device A/B review.

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


## Quality hardening and measurement protocol — 14 November 2026

The producer was tightened after reviewing the stable BaselineProfileRule and Macrobenchmark APIs:

- The profile collection test calls pressHome() before startActivityAndWait() so every iteration begins at the launcher.
- strictStability is true. The generator keeps 15 maximum iterations and 3 stable iterations and must fail rather than accept unstable output.
- The producer test APK uses maxOf(28, app minSdk). This respects BaselineProfileRule's API 28+ rooted / API 33+ non-rooted support without changing Nuvio's production minSdk 24.
- A separate manual StartupBenchmark compares CompilationMode.None() with CompilationMode.Partial(BaselineProfileMode.Require), using StartupMode.COLD, StartupTimingMetric and 10 iterations. The profile-enabled scenario requires a packaged profile and will fail when it is absent.
- The Macrobenchmark tests require androidx.benchmark.enabledRules=macrobenchmark and are not linked to release assembly or CI.

Measurement limitations:
- The benchmark guarantees TTID. Current Android MainActivity does not wire the app-ready callback into Compose ReportDrawnWhen, so TTFD cannot yet be treated as validated. Do not use an arbitrary sleep as a substitute for a real user-interactive readiness signal.
- The profile collection journey captures the installed app's actual launcher state. It does not synthesize sign-in state, onboarding completion, catalog data or add-on/network responses. A stable signed-in Home critical journey requires an approved test-data fixture.
- Generated baseline-prof.txt and startup-prof.txt still need to be produced for each release flavor. Both generated variants must be inspected, and the compiled binary Baseline Profile must remain below Android's 1.5 MB limit.
- The A/B comparison must use the same physical device and matching app flavor/build configuration. Compare median TTID and keep the profile only if it does not regress. Do not treat emulator timings as user-facing performance evidence.

These changes add the source-side collection and measurement mechanism only. No profile is generated or included in a release yet, and no Nuvio speedup is claimed.


## Startup readiness measurement update — 14 November 2026

The Android launch path now uses the existing AppGate readiness signal to report a true fully-drawn state. MainActivity passes App(onAppReady = ...) into remembered state and supplies that state to Compose ReportDrawnWhen. This does not introduce a fixed delay, alter navigation, force network completion, or change what users see.

A root-level Compose test tag, nuvio_startup_ready, is mapped through testTagsAsResourceId for UI Automator. It appears only after the same main-content-ready callback. The wrapper remains a single full-size root container so the marker does not add another nested layout layer.

The manual StartupBenchmark waits for this marker after startActivityAndWait(). Android documents that the latter waits for the first frame only; a benchmark must wait for the actual content-ready element inside the measured block for TTFD to be captured. The benchmark timeout is a condition-based failure, not a sleep.

The Home readiness measurement requires a preconfigured signed-in test installation. Login/onboarding is not considered the Home-ready state. The benchmark harness and runtime callback wiring remain uncompiled and unexecuted until the user authorizes build/device validation. This source change improves measurement quality; it does not itself make startup faster.

References:
- https://developer.android.com/topic/performance/benchmarking-macrobenchmark-metrics
- https://developer.android.com/codelabs/android-macrobenchmark-inspect
- https://developer.android.com/reference/kotlin/androidx/activity/compose/ReportDrawnWhen.composable
- https://developer.android.com/develop/ui/compose/testing/interoperability
- https://developer.android.com/jetpack/androidx/releases/test-uiautomator


## Visible-ready state refinement — 14 November 2026

The readiness callback now accounts for the launch overlay's exit transition. Home data readiness alone is not the same as the visible app being ready: the overlay can still be animating away. MainAppContent therefore reports ready only when root content is ready, launchOverlayState is idle, and its current state is hidden. The readiness effect observes both transition-state properties so it is re-evaluated when the overlay exits.

This does not change the overlay animation or hold up content rendering. It corrects the definition used by ReportDrawnWhen and the startup benchmark marker. Runtime validation remains deferred until authorized Compose tests and physical-device benchmarking.


## Authorized R8 build and device run update — 14 November 2026

The user authorized an optimized Android build and validation. The workflow now builds the AndroidX `benchmarkRelease` variants for both `full` and `playstore`. AndroidX creates this variant from the release build configuration with R8/minification and resource shrinking enabled, non-debuggable and profileable; it uses the local debug signing key so Macrobenchmark can install it without production signing secrets. It is not a production-signed public release APK. The separate `nonMinifiedRelease` build remains the profile-collection target, as required by Android's Baseline Profile workflow.

The generator now waits for `nuvio_startup_ready` after launching the app. It rejects first-run authentication/onboarding as a completed journey. The physical-device runner uses the variant-specific output paths `androidApp/src/fullRelease/generated/baselineProfiles` and `androidApp/src/playstoreRelease/generated/baselineProfiles`, and then compares 10 cold starts in `CompilationMode.None()` with 10 cold starts in `CompilationMode.Partial(BaselineProfileMode.Require)` for each flavor using the same physical device and signed-in install. The app must be pre-signed-in; the script does not create accounts or falsify remote catalog state.

The fork has no production release signing secrets. This does not block the benchmarkRelease variant because Nuvio explicitly assigns the Android debug signing config to the profiling-only app variants and leaves the production release signing config unchanged. A production-signed copy must be uninstalled once before installing this benchmark-signature APK; the user signs in again on the benchmark installation. The two flavors share the application ID and benchmark key, so test state can be retained when switching flavors.

The CI build workflow is compiling the first R8 benchmark variant. The physical-device runner cannot be executed until a supported physical device is connected through ADB. Therefore no per-flavor profile has been accepted and no TTID/TTFD median or Nuvio startup speedup is claimed yet. Acceptance requires a repeatable improvement in both metrics with no regression and a compiled profile below Android's 1.5 MB binary limit. The legacy shared main profile remains untouched pending that result.


## Improvement cycles 44–46 — isolate Android profile runs and make device validation phone-only (14 November 2026)

### Upstream NuvioMobile comparison

Compared the live upstream repository `NuvioMedia/NuvioMobile` (branch `cmp-rewrite`, commit `9d1386b83fb18488b4547a5a20f6e89ee83b1d8d`) with this fork's `main-build-testing` implementation.

- Upstream has broader Baseline Profile collection journeys: launcher startup, Home scrolling, hero/poster detail screens and tabs. This is useful as a future profile-coverage reference.
- The upstream generator uses fixed sleeps for screen transitions and catches journey exceptions to log warnings. That can produce incomplete coverage while allowing a nominally successful collection. I did not copy those failure semantics or arbitrary waits.
- The upstream search did not find a `MacrobenchmarkRule`, `StartupTimingMetric` or controlled cold-start A/B benchmark. Its generator is a broader profile producer, not a better TTID/TTFD measurement harness.
- The enhanced fork retains the stronger startup comparison: `StartupTimingMetric`, cold launches, 10 iterations per arm, `CompilationMode.None` against `CompilationMode.Partial(BaselineProfileMode.Require)`, and a real Home-ready marker that fails the run when sign-in/onboarding is incomplete.
- Broader journeys remain a follow-up candidate. They should be added only with condition-based UI Automator waits, stable selectors and failing assertions; fixed sleeps and swallowed journey failures are not acceptable evidence.

### Cycle 44 — protect production app identity and signed-in data

- Added `.benchmark` application-ID suffix to both AndroidX profiling-only build types: `benchmarkRelease` and `nonMinifiedRelease`. Production `release` retains `com.nuvio.media` and its release signing configuration is unchanged.
- Both profiling build types deliberately share the same `com.nuvio.media.benchmark` identity and debug signing config. Within one workflow artifact, switching from non-minified profile capture to the R8 Macrobenchmark build, and from full to playstore, updates the same isolated install rather than deleting its signed-in state.
- Removed hardcoded `com.nuvio.media` targets from the producer and benchmark tests. Both resolve the installed target ID with the instrumentation's target application context, so the tests follow the variant actually under test.
- Updated the desktop runner to launch and report the isolated benchmark package. Its failure guidance now says to remove only the isolated `.benchmark` package when an older artifact's signing key conflicts; it never asks the user to uninstall production Nuvio.

### Cycle 45 — collect and measure directly on the Samsung phone

- Added `scripts/nuvio-phone-benchmark.sh`, a POSIX shell runner for LADB / Android Wireless Debugging. It uses Android's shell `pm install` and `am instrument` commands, so no computer, local Gradle checkout, USB ADB host, or manual APK paths are required.
- The bundle now includes eight APKs: nonMinifiedRelease app/test pairs for profile capture and R8 benchmarkRelease app/test pairs for measurement, for both full and playstore.
- Enforced sequence per flavor: install non-minified pair, collect the real startup profile, install the R8 benchmark pair, and measure the two cold-start compilation modes. A state guard refuses collection/measurement against the wrong installed variant.
- Runner validates a physical Android API 33+ device, discovers the instrumentation component from Package Manager rather than hardcoding its test APK package, waits for the instrumentation process, and copies newly written profile text, benchmark JSON and traces from Android shared media output into `Download/NuvioStartupBenchmark`.
- Profile text collected on the phone is a candidate only. It is not silently injected into the R8 APK. The currently packaged profile is what the A/B macrobenchmark measures; after reviewing candidate text, a separate source update and explicitly authorized build are needed to measure the new profile.
- Added `scripts/tests/test-nuvio-phone-benchmark.sh` with mocked Android shell commands. It covers both flavors, API 32 rejection, invalid run-order rejection, profile/JSON export and the invariant that production `com.nuvio.media` is never uninstalled.

### Cycle 46 — prevent accidental builds and document the no-computer route

- Changed `.github/workflows/android-startup-profile-benchmark.yml` to `workflow_dispatch` only. Pushing source changes to `main-build-testing` cannot trigger this Android build; a separate explicit manual dispatch is required.
- The manual workflow now assembles the nonMinifiedRelease and benchmarkRelease app/test pairs for both flavors, validates the phone runner and its mock test, packages the eight APKs and LADB instructions, and uploads a 14-day artifact.
- Updated the artifact's instructions to explain the LADB pairing steps, the exact commands to paste, the production-app isolation, the results folder and the distinction between collected candidate rules and rules already packaged in the measured APK.

### Verification and limits

- Passed local static checks: `sh -n scripts/nuvio-phone-benchmark.sh`, `bash -n scripts/run-android-startup-profile-benchmark.sh`, and the mocked-shell test `sh scripts/tests/test-nuvio-phone-benchmark.sh`. The test reports PASS for both variants, unsupported API rejection, run-order guards and profile/benchmark output copying.
- Re-fetched the source/workflow files from GitHub after updates. This is source/API and shell-mock verification, not Android device verification.
- Context7 exact-library resolution was attempted but the service returned its monthly quota limit. The Android package/Instrumentation decisions were checked against Android Developers API/build-variant documentation and AndroidX source through Exa; no successful Context7 retrieval is claimed.
- No Gradle task, build, GitHub Actions run, Macrobenchmark, profile generation or physical-device instrumentation was started for this follow-up, in accordance with the current no-build/no-CI instruction.
- The previous four-APK artifact is stale for this flow: it uses the production package ID and does not include the two nonMinifiedRelease pairs. Do not install that older artifact. The new eight-APK bundle requires one explicitly authorized manual workflow dispatch.
- No TTID/TTFD medians, generated profile acceptance or Nuvio speedup are claimed. Physical-device results remain outstanding.

References:
- Android Baseline Profile generation: https://developer.android.com/topic/performance/baselineprofiles/overview
- Macrobenchmark instrumentation from shell / CI: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args
- Android instrumentation target context: https://developer.android.com/reference/android/app/Instrumentation#getTargetContext()
- Android build variants and application ID suffix: https://developer.android.com/build/build-variants
- LADB pairing procedure and implementation: https://github.com/tytydraco/LADB


## Follow-up design invariant — profile capture must use the isolated non-minified variant

The desktop and phone-only execution paths must collect rules from the same target identity:

- Profile capture uses each flavor's `nonMinifiedRelease` app/test pair. This variant is non-minified for accurate method signatures, profileable for collection, and uses the isolated `com.nuvio.media.benchmark` application ID and benchmark signing key.
- Macrobenchmark uses the matching flavor's `benchmarkRelease` app/test pair with R8/minification enabled and the same isolated application ID/signing identity.
- Do not invoke the production `fullRelease` or `playstoreRelease` profile-generation tasks from the desktop runner. The correct per-flavor tasks are `generateFullNonMinifiedReleaseBaselineProfile` and `generatePlaystoreNonMinifiedReleaseBaselineProfile`; generated rules are inspected under `src/<flavor>NonMinifiedRelease/generated/baselineProfiles`.
- Keep the production `com.nuvio.media` package and release signing configuration unchanged. A desktop or phone-only profile run must never require uninstalling or replacing the production app.

A regression test now checks task names, generated source-set path and ADB carriage-return filtering before any future authorized benchmark build. No new build or CI execution is part of this follow-up.


### Integration record — 14 November 2026

- Final review compared `fix/android-startup-profile-shell-validation` against the then-current `main-build-testing` head `8dca68e7b1c92997af5d9eb6e4b2187872d19c4e`: 8 commits ahead, 0 behind, with changes limited to the desktop benchmark runner, its mock-test script and four engineering/design notes.
- Fast-forward integrated the reviewed branch into `main-build-testing` at `ef23a761d5a27b40866ed0bace6cb5cdcb242797`. No force update or merge conflict was used.
- The integrated diff does not modify `androidApp/build.gradle.kts`, application IDs, manifests, Kotlin/Compose app source, the production release signing configuration or the phone-only runner.
- No new Actions run was triggered by this integration. The only observed active benchmark run remains [43652201558](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43652201558), which was already in flight from the separate CI-dispatch branch. At the latest check, both `nonMinifiedRelease` profile-capture build steps had succeeded and `Build full R8 benchmark APK pair` was in progress. Do not treat this as a completed artifact until the workflow reports success and the artifact is verified.
- The full updated mock suite has not been rerun locally after adding the new assertions. The focused local RED/GREEN regression fixture and shell syntax fixture passed; no Gradle build, CI run, Macrobenchmark or device test was started by this integration.
