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


## Source integration commit record

All source and documentation changes are committed directly to `main-build-testing`:

- Design: `1cdf702433a2793ef9c2683759e08d9325c93ed6`
- Plan: `b3d483155e9b3658ad4c015aa6bbd71f1d883f1d`
- Version catalog: `152237ecd8ecbedd02661afcb65bd5e76699d6c7`
- Root plugin registration: `ce9b4f152253b1bf510ce32c45c284234ccaeaf5`
- Producer module inclusion: `47fe9f1f9b905a245e611d1ad7f21a5e934a1c7f`
- App consumer configuration: `c4b33b1e27dbe81df831946b5ce12e8c387fe986`
- Producer module: `534e41080613c5a9fec247c741e91bf8c928b3e8`
- Startup journey: `8411514ee27a69f962f3265e35b6068dbb7f0af3`
- Startup review notes: `2dfe5f0a7ce65a83e80c3374cb096b60519e1fdc`
- Cumulative improvement notes: `6b5faa47c0f0b002b24d235989f63536643cb877`

## Static review record

The fresh GitHub comparison from starting head `ba94eaaa32da394473f39532e6cb0b65fdbc7e82` to the reviewed head `6b5faa47c0f0b002b24d235989f63536643cb877` reports 10 commits ahead and 0 behind, with exactly the 10 paths listed in the commit record above. No GitHub Actions or Codemagic workflow files changed.

Remote source checks confirmed:
- Baseline Profile and Macrobenchmark are both pinned to stable 1.5.0.
- The `:baselineProfile` module is included and targets `:androidApp`.
- Producer flavors match the app's `full` and `playstore` flavor names and `distribution` dimension.
- The generator uses `com.nuvio.media` and marks only the launcher-start journey for the Startup Profile.
- App minification, the existing ProfileInstaller dependency, release application ID, and debug-only application ID override are unchanged.
- Variant source output is enabled and automatic generation during release assembly is explicitly false.

Review was a static self-review against the design and official AndroidX/Android Developer documentation. No independent reviewer or subagent was available. No build, configuration task, test, device run, Macrobenchmark or CI workflow was executed. All deferred validation items remain open.


## Follow-up source quality cycles (40–41)

- [x] Verify the BaselineProfileRule Java overload and supported device API levels against current AndroidX API/source.
- [x] Reset each profile collection iteration to the device launcher with pressHome().
- [x] Require strict profile stability within the existing 15-iteration / 3-stable-iteration limit.
- [x] Restrict only the producer test APK to API 28+ (or the app's higher minSdk); preserve production app minSdk 24.
- [x] Add an explicit cold-start A/B Macrobenchmark: no compilation versus required packaged Baseline Profile, cold startup, 10 iterations.
- [x] Record that TTID is the guaranteed benchmark output; TTFD requires a real Compose fully-drawn signal that Nuvio does not currently wire.
- [x] Record that the current startup scenario does not fabricate a signed-in Home state or external catalog/add-on data.
- [x] Update the design and both engineering notes, then re-fetch the exact remote files.

## Additional source commits

- Deterministic, strict collection and launcher reset: 1ffb1385c12c6b4b09cac84d5eb7777dc7b3c2c4
- Generator API minimum: 2ba0eae26dfce041d20b4fe1f9dcdf520c4453f2
- Cold-start A/B benchmark: 8ed9ded4f0877ac6150eda8dcedcdbb821eec32c
- Startup critical-path review update: 0e5727c6203fb33a48c7e32e09283f7aa6d98c16
- Cumulative improvement notes update: fca847f7d0b25f1795714275f2e2bed30d450197

## Review boundary for cycles 40–41

The Java collection and benchmark calls were cross-checked against the AndroidX BaselineProfileRule and MacrobenchmarkRule API signatures, CompilationMode classes, BaselineProfileMode enum, StartupMode enum and official Android performance guidance. Review is static and author-performed; no independent reviewer was available. No Gradle configuration, build, compilation, test, device operation, profile generation, Macrobenchmark or CI was run. The benchmark tests are source additions only until an authorized run verifies the module task/runner-argument wiring and compilation.


## Source quality cycle 42 — Fully-drawn startup measurement

- [x] Wire Android MainActivity's existing App readiness callback to Compose ReportDrawnWhen.
- [x] Expose a test-only Compose tag mapped to a UI Automator resource ID only after the same readiness callback.
- [x] Wait for that marker inside the measured Macrobenchmark block, with an explicit 30-second failure instead of arbitrary sleep.
- [x] Pin stable UI Automator 2.4.1 only in the producer/test module.
- [x] Keep readiness marker on the existing root wrapper to avoid an unnecessary extra layout layer.
- [x] Document that this is a measurement improvement, not a user-visible speed optimization.
- [ ] Verify Kotlin/Java/Gradle compilation and instrumentation runner wiring in an authorized build session.
- [ ] Run both A/B cases on the same physical device and preconfigured signed-in test state; inspect TTID and TTFD medians.
- [ ] Generate and inspect real profile files for both release variants; do not claim a speedup until measured.

Static review details:
- Android's Macrobenchmark codelab explicitly states that startActivityAndWait() waits only for the first frame and the measured block must separately wait for content readiness to capture TTFD.
- ReportDrawnWhen is the Compose-native API for state-dependent fully-drawn reporting.
- testTagsAsResourceId is the documented Compose/UI Automator bridge; the tag is exposed only after App(onAppReady) sets the state.
- UI Automator 2.4.1 is the stable release published 4 November 2026.
- Context7 quota was exhausted. Exa was used to consult official Android Developers documentation and release references.
- No build, test, Macrobenchmark, profile generation, device operation or CI was run.


## Source quality cycle 43 — Wait for the visible Home state, not just its data

- [x] Trace the App -> AppGate -> MainAppContent readiness callback to its origin.
- [x] Identify that root Home readiness can precede completion of the launch overlay exit animation.
- [x] Include launchOverlayState.currentState and launchOverlayState.isIdle in the readiness effect keys.
- [x] Report ready only after root content is ready and the launch overlay is fully idle and hidden.
- [x] Keep the visual transition unchanged; update only the readiness signal consumed by TTFD.
- [ ] Verify callback behavior with Compose tests and a physical-device startup benchmark when authorized.

No startup speedup is claimed. This makes the future TTFD comparison represent the visible ready state instead of an earlier underlying-content state.
