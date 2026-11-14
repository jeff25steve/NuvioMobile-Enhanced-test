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
