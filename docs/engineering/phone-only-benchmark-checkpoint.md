# Phone-only Android startup benchmark checkpoint

Date: 12 November 2026

## Purpose

Build the existing Macrobenchmark app and instrumentation APKs in GitHub Actions so a physical Android phone can run the startup comparison without a laptop.

## Implementation

- Workflow: `.github/workflows/android-phone-benchmark.yml`
- Branch: `main-build-testing`
- Workflow run: https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43157348602
- Workflow commit: `dae72916bef4b02ad7b5cb376940d56bb49f3ab8`
- The workflow builds `:androidApp:assembleFullBenchmark` and `:baselineprofile:assembleFullBenchmark`, then packages the two APKs, a start-here note, and SHA-256 checksums into one GitHub Actions artifact.
- The app uses the existing `benchmark` build type, which inherits release configuration and is signed with the debug key for installability. The instrumentation module reuses the existing AndroidX Macrobenchmark 1.5.0 dependencies.
- No app runtime behavior, UI, dependency version, or production release configuration was changed in this workflow setup.

## Current validation status

At the time of this checkpoint, GitHub Actions run #1 was in progress. Setup, Java, Gradle, runtime properties and Rust Android targets had completed; the Gradle build of both benchmark APKs was running.

No APK artifact had yet been uploaded, and no physical-device measurement had been collected. Do not describe the workflow as passing until the run succeeds and the artifact is present.

## Phone execution constraints

- The benchmark must run on a physical Android device, not a GitHub-hosted runner or emulator.
- The target app package is `com.nuvio.media`; the instrumentation module namespace is `com.nuvio.baselineprofile`.
- The two instrumentation methods each run ten cold-start iterations: without AOT compilation and with the required Baseline Profile.
- The first metric is cold-start TTID from `StartupTimingMetric`. It is not a home-content TTFD or a complete time-to-interactive measurement.
- Android instrumentation test APKs may require the ADB install test-only flag. Confirm the final APK metadata and installation command before handing the bundle to the user.
- The next user-facing step should be minimal and device-specific; do not ask the user to handle Gradle, source code, or GitHub workflow configuration.
