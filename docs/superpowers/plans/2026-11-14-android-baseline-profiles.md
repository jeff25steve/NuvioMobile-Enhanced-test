# Plan — Android Baseline Profile integration

**Date:** 14 November 2026  
**Branch:** `main-build-testing`  
**Design:** `docs/superpowers/specs/2026-11-14-android-baseline-profiles-design.md`

## Scope

Integrate a stable AndroidX Baseline Profile producer and a narrowly scoped cold-start collection journey for the existing Android app. Keep generation manual and outside release/CI. Do not build, test, benchmark, or run CI without the user's authorization.

## Implementation

- [x] Confirm current branch head and inspect app module, version catalog, wrapper, manifest, and existing profile files.
- [x] Verify that ProfileInstaller 1.4.1 and the legacy shared `src/main/baseline-prof.txt` (about 1.4 MB of source rules) are present; identify the missing per-flavor profile generation and measurement path.
- [x] Confirm Android's official baseline-profile toolchain guidance, AGP 9.4.0 / Gradle 9.8.2 compatibility, Macrobenchmark 1.5.0 release status, and generator API.
- [x] Add version-catalog aliases for the stable Baseline Profile plugin, Android test plugin, Macrobenchmark 1.5.0, and test dependencies.
- [x] Register the producer module and configure matching `full` and `playstore` variants.
- [x] Integrate the producer with the app while explicitly keeping automatic generation during release builds disabled.
- [x] Add a startup-only profile collection test targeting the release application ID.
- [x] Update the Android startup review and cumulative codebase improvement notes.
- [x] Perform a separate static review of variants, IDs, R8/minification, generator scope, and release-build triggers.
- [x] Re-fetch changed files and verify the resulting remote branch state.

## Authorized build and physical-device validation

The user subsequently authorized Gradle builds and CI. The initial measurement pipeline is now implemented:

- [x] Add a signed-in Home readiness guard to the generator using `nuvio_startup_ready`.
- [x] Add a physical-device runner for `fullRelease` and `playstoreRelease` profile generation and 10-iteration cold-start A/B runs.
- [x] Add an Actions workflow to produce AndroidX `benchmarkRelease` app/test APK pairs for both flavors.
- [x] Keep profile generation variant-specific (`fullRelease` / `playstoreRelease`) and leave the legacy shared `src/main/baseline-prof.txt` intact.
- [x] Exclude AndroidX `BenchmarkRelease` and `NonMinifiedRelease` tasks from production ABI-split detection so the Macrobenchmark app APK remains universal.
- [ ] Complete and review the R8 `benchmarkRelease` APK builds for both flavors.
- [ ] Run the generator and the cold-start Macrobenchmarks on the same physical device with one signed-in Home test state.
- [ ] Compare TTID and TTFD medians for both flavors, inspect raw traces and profile binary sizes, and accept/reject generated profiles based on repeatable non-regressing results.

AndroidX's `benchmarkRelease` is a non-debuggable, profileable, R8/minified, resource-shrunk release-mirrored build. The app module explicitly assigns the local Android debug signing config to `benchmarkRelease` and `nonMinifiedRelease`, since this fork's production `release` signing config has no keystore. It is the supported Macrobenchmark target and is not a production-signed release artifact. If a production-signed app is installed on the physical device, it must be uninstalled once before the benchmark-signed variant can be installed; sign in to the benchmark build afterward and retain its data between the two flavor runs.

## Execution boundary

The source-side measurement path and the R8 benchmark packaging workflow are now implemented. A GitHub build is in progress, but a physical device is not attached to this engineering environment. No generated per-flavor profile is accepted, and no Nuvio speedup or TTID/TTFD medians are claimed until the same-device signed-in comparison has completed. Keep the legacy shared profile unchanged until the controlled comparison determines whether the new per-flavor rules improve startup without regressions.

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


## Follow-up repository corrections — 14 November 2026

The initial plan incorrectly described `androidApp/src/main/baseline-prof.txt` as empty. A remote size check showed 1,409,345 bytes of existing shared source rules committed in September 2026. The file remains unchanged; new generated files belong under `androidApp/src/fullRelease/generated/baselineProfiles` and `androidApp/src/playstoreRelease/generated/baselineProfiles` with `mergeIntoMain = false`.

The first attempt to assemble production-signed release APKs could not proceed because this fork has no release keystore/local-properties secrets. This is not required for Macrobenchmark: the AndroidX plugin's `benchmarkRelease` app variant is explicitly configured as non-debuggable, profileable, R8/minified and debug-key signed. The dedicated workflow therefore builds those release-mirrored benchmark variants rather than an unsigned or debug app build. Production release signing remains untouched.


## Build verification result — 14 November 2026

- [x] Build the full R8 benchmarkRelease app and Macrobenchmark APK.
- [x] Build the playstore R8 benchmarkRelease app and Macrobenchmark APK.
- [x] Upload and verify the phone benchmark bundle (Actions run 43645267759, artifact 14134457830).
- [ ] Connect one physical device and run the updated branch's generator and 10-iteration A/B tests for both flavors.
- [ ] Inspect TTID/TTFD medians and binary profile sizes, then accept or reject generated per-flavor profiles.

The CI APK build passed. The uploaded 344,398,688-byte archive was downloaded and passed ZIP integrity validation. This does not satisfy the device benchmark acceptance criteria; no TTID/TTFD medians or performance improvement are claimed yet.


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


## Follow-up — CI validation and desktop variant alignment (14 November 2026)

- [x] Trace the first failed run to the desktop runner's doubled `find` escapes; correction committed on `main-build-testing`.
- [x] Trace the second failed run to the mock test's incorrect relative runner path; correction committed on `main-build-testing`.
- [x] Correct six ADB output filters from `tr -d '\\\\r'` to `tr -d '\\r'` so carriage-return bytes are actually removed.
- [x] Align desktop profile generation with the isolated app identity: use `generateFullNonMinifiedReleaseBaselineProfile` and `generatePlaystoreNonMinifiedReleaseBaselineProfile`, and inspect `src/<flavor>NonMinifiedRelease/generated/baselineProfiles` rather than the production `release` source set.
- [x] Add regression assertions for ADB CR cleanup, both profile tasks and the generated profile path.
- [ ] Run the complete updated mock suite and desktop runner syntax check in a later explicitly authorized validation session.
- [ ] Complete and review the already-existing Actions run 43652201558; do not start another run without authorization.
- [ ] Complete physical-device profile collection and TTID/TTFD A/B measurement.

The follow-up changes are on `fix/android-startup-profile-shell-validation`, based on the current `main-build-testing` head. This session did not run Gradle, start CI, dispatch a workflow or operate a device. The previously active run 43652201558 had already passed the pre-build gate and was observed building the first non-minified profile-capture pair; that status is not equivalent to a completed artifact.

Context7's monthly quota prevented retrieval of AndroidX Baseline Profile 1.5.0 documentation. The variant mapping was checked against the Android Developers plugin configuration documentation and AndroidX plugin source using Exa. No profile or performance improvement is accepted until the physical-device comparison is complete.
