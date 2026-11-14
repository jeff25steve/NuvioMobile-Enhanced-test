# Plan — Android Baseline Profile integration

**Date:** 14 November 2026  
**Branch:** `main-build-testing`  
**Design:** `docs/superpowers/specs/2026-11-14-android-baseline-profiles-design.md`

## Scope

Integrate a stable AndroidX Baseline Profile producer and a narrowly scoped cold-start collection journey for the existing Android app. Keep generation manual and outside release/CI. Do not build, test, benchmark, or run CI without the user's authorization.

## Implementation

- [x] Confirm current branch head and inspect app module, version catalog, wrapper, manifest, and existing profile files.
- [x] Verify that ProfileInstaller 1.4.1 is present and the app-owned baseline profile file is empty.
- [x] Confirm Android's official baseline-profile toolchain guidance, AGP 9.4.0 / Gradle 9.8.2 compatibility, Macrobenchmark 1.5.0 release status, and generator API.
- [x] Add version-catalog aliases for the stable Baseline Profile plugin, Android test plugin, Macrobenchmark 1.5.0, and test dependencies.
- [x] Register the producer module and configure matching `full` and `playstore` variants.
- [x] Integrate the producer with the app while explicitly keeping automatic generation during release builds disabled.
- [x] Add a startup-only profile collection test targeting the release application ID.
- [x] Update the Android startup review and cumulative codebase improvement notes.
- [x] Perform a separate static review of variants, IDs, R8/minification, generator scope, and release-build triggers.
- [x] Re-fetch changed files and verify the resulting remote branch state.

## Deferred by explicit build/CI restriction

- [ ] Run Gradle configuration/compile checks for the new module.
- [ ] Run the profile generator on a rooted emulator or API 33+ supported device.
- [ ] Review and commit real generated Baseline Profile and Startup Profile rule files for both release flavors.
- [ ] Run repeated cold-start Macrobenchmarks with and without the profiles on the same physical device and compare TTID/TTFD.
- [ ] Accept or reject the resulting profile set based on measured results and binary profile size.

## Execution boundary

The source integration is complete as a repository change, but the performance improvement is **not yet delivered or measured** because profile generation and all Gradle/device operations are intentionally deferred. Do not describe the empty source profile as an active optimization. Do not claim a Nuvio speedup until a generated profile is packaged and compared in an authorized benchmark.

Context7 was attempted for the exact AndroidX Baseline Profile plugin but the monthly quota was exhausted. Official Android documentation, AndroidX release notes/source, and repository configuration were used instead. No build, test, Macrobenchmark, device run, or CI task was executed.
