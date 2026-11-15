# Android startup benchmark phone workflow correction — 2026-11-15

## User-visible failure

The first phone bundle installed both APKs successfully, but the phone runner stopped with:

`The matching test APK is not installed or does not target com.nuvio.media.benchmark`.

Device diagnostics showed:
- App under test: `com.nuvio.media.benchmark`
- Self-instrumenting test APK: `com.nuvio.android.baselineprofile`
- Android instrumentation target: `com.nuvio.android.baselineprofile`

This is expected for the `com.android.test` module with
`android.experimental.self-instrumenting=true`. The runner had incorrectly
required the instrumentation target to equal the app-under-test package.
Additionally, the test sources derived the target app package from
`InstrumentationRegistry.getTargetContext()`, which resolves to the
self-instrumenting test APK rather than the Nuvio app.

## Corrections

- The phone runner now resolves the self-instrumenting runner component against
  `com.nuvio.android.baselineprofile`.
- Both Baseline Profile generation and Macrobenchmark explicitly target
  `com.nuvio.media.benchmark`, the isolated application ID used by both
  distribution flavors' benchmark build types.
- The runner automatically stages only the APK pair needed for the selected step, plus itself, into
  `/data/local/tmp/NuvioBenchmark`; users no longer need a separate manual copy
  step to work around Package Manager's inability to install from shared
  Downloads storage.
- Added a single `run <flavor>` command. After installing the profile-capture
  pair and signing in once, it collects the candidate profile, installs the
  benchmark pair, and runs the startup measurements.
- Replacing an APK signed by a previous workflow run is handled safely: only
  `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile` may be
  removed on an explicit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` response. The
  production `com.nuvio.media` app is never uninstalled or cleared.
- Updated the generated `START-HERE.txt` instructions to use a fresh
  `NuvioBenchmark-Fixed` folder and the simplified commands.

## Verification

The phone-runner regression test now models the actual self-instrumenting
instrumentation line and checks automatic staging, the test/app package
separation, both distribution flavors, the combined run command, result export,
and the production-package safety guard. The manual workflow also runs shell
syntax validation and the regression test before any Android APK assembly.

## Important measurement distinction

The collected profile text is a candidate output. The Macrobenchmark's
profile-enabled measurement still uses the Baseline Profile packaged in the
benchmark APK. A newly collected candidate is not silently injected into the
already-built APK; review and a separate authorized build are required before
measuring a new profile.


## Follow-up device failure — 2026-11-15

The second real-phone run reached instrumentation successfully but failed only
at the 30-second wait for `nuvio_startup_ready`. The complete uploaded
`instrumentation.log` contains one test failure and no installation or runner
failure. The supplied screenshots show the benchmark app had reached the
signed-in Home UI before the repeated attempt. Asking the user to sign in again
was therefore not supported by the evidence.

### Corrected diagnosis

The earlier explanation that Compose cannot expose a `testTag` placed on the
same layout node as `testTagsAsResourceId` was incorrect. Reviewing the
AndroidX Compose accessibility implementation showed that resource-ID mapping
checks the tag's own semantics node and then walks its ancestors. Therefore,
the original same-node modifier arrangement was valid, and the temporary
nested-Box change was removed. The selector form `By.res("tag")` was also
correct.

A weakness in the previous test was that its only route-readiness
check was coupled to `MainActivity`'s separate `appReady` callback. That
callback becomes true only after AppGate reports main-content readiness and
the launch overlay has exited. The phone screenshot proves Home was visible
before the repeated benchmark attempt, but does not prove which readiness state
was reached during the later cold launch. This coupling is a plausible source
of the mismatch, not a proven root cause; the two markers now distinguish the
Home route from the fully-drawn signal so the next device run can identify
which stage is missing.

### New benchmark contract

- `HomeScreen` exposes an unconditional `nuvio_benchmark_home` test tag on
  its root `BoxWithConstraints`. The app root enables `testTagsAsResourceId`,
  so UI Automator can locate it with `By.res("nuvio_benchmark_home")`.
- `MainActivity` retains the conditional `nuvio_startup_ready` test tag,
  attached when `appReady` becomes true. It is still the same state supplied
  to `ReportDrawnWhen { appReady.value }`.
- Both instrumentation paths use one shared 30-second elapsed-realtime budget:
  first wait for the HomeScreen route marker, then use the remaining time to
  wait for the fully-drawn marker. This distinguishes “Home never mounted”
  from “Home mounted, but AppGate/launch-overlay readiness never completed”.
- The second marker prevents the benchmark action from ending as soon as the
  Home composable mounts. The Macrobenchmark still waits for the app's actual
  fully-drawn state, preserving TTFD coverage rather than confusing Home route
  availability with full display.

### Failure diagnostics and regression protection

- The readiness regression script verifies the app-root semantics mapping,
  both Home/fully-drawn markers, both benchmark selectors, and the shared
  30-second deadline.
- Both Baseline Profile collection and Macrobenchmark include the foreground
  package and an accessibility hierarchy snapshot if the marker still times
  out.
- The diagnostic hierarchy redacts every `text` and `content-desc` attribute
  value and is capped at 8,000 characters.
- The original `nuvio_startup_ready` Activity-level marker is no longer used
  as the benchmark's route detector.

### Verification boundary

The readiness regression was exercised against synthetic source fixtures:
the old Activity-only marker contract failed at the missing HomeScreen marker,
and the HomeScreen plus fully-drawn contract passed. The actual GitHub branch
sources were then fetched and checked for both markers, both selectors, the
shared deadline, retained `ReportDrawnWhen`, and diagnostic redaction. No
Gradle build, device benchmark, or CI run was started. A real-device run remains
necessary to confirm that Samsung's accessibility hierarchy exposes both
markers and to collect meaningful TTID/TTFD measurements. No startup improvement
is claimed.

References:
- [Compose interoperability with UiAutomator](https://developer.android.com/develop/ui/compose/testing/interoperability)
- [AndroidX Compose accessibility implementation](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt)
- [Macrobenchmark startup metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)


## CI build verification

The corrected phone workflow regression suite passed, and all four APK-pair build
steps passed in GitHub Actions run
[43657203014](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43657203014).

The resulting phone bundle artifact is:
- Name: `nuvio-android-startup-profile-0a7e3c2e15ebcb59e16e46c80a6b30a392d92f67`
- Artifact ID: `14139057608`
- Size: 688,797,198 bytes
- SHA-256: `7d29794dfdd5fa8f71ae3f5f819e6fd4cbcde43ffea4d114a8133fddd0b22704`
- Expires: 2026-11-29

The build was run from a short-lived verification branch whose only difference
from `main-build-testing` was a temporary push trigger for this workflow. The
APK sources, test sources, phone runner, regression test, and user instructions
were identical to `main-build-testing` at the time of the build.

The bundle has been built and uploaded successfully. A real-device execution
of the corrected instrumentation and startup measurements is still pending;
no startup performance improvement is claimed until the phone results are
collected and compared.


## Follow-up after the 03:03 phone run

The user confirmed that no other app was open during the run, so the earlier split-screen hypothesis is discarded.

The uploaded LADB output establishes two separate facts:

1. The HomeScreen selector succeeded: the test reported that HomeScreen appeared.
2. The fully-drawn selector did not become visible within the remaining 30-second deadline.

The diagnostic then attempted `UiDevice.dumpWindowHierarchy(OutputStream)` and itself failed with `IllegalArgumentException: Illegal character (U+0)`. Android's accessibility hierarchy dumper serializes node attributes as XML; invalid control characters in accessibility text can make that serializer throw. This was a secondary diagnostics failure, not evidence that Nuvio crashed. The screenshots therefore do not identify which AppGate readiness condition remained false.

The previous label `Foreground package` was also inaccurate: `UiDevice.getCurrentPackageName()` is documented as the last package to report an accessibility event, not a guaranteed foreground-window query. The diagnostic now labels it accordingly.

### Source correction

- Removed XML hierarchy dumping from both instrumentation failure paths.
- Diagnostics now report the last accessibility-event package and whether each of the two marker selectors is visible, using best-effort calls that cannot replace the original assertion.
- Kept the HomeScreen and fully-drawn waits and the shared elapsed-realtime deadline unchanged. This preserves the distinction between Home route mounting and the actual TTFD signal.
- Kept `ReportDrawnWhen { appReady.value }` unchanged. AndroidX documents `StartupTimingMetric.timeToFullDisplayMs` as ending when the app reports fully drawn through `Activity.reportFullyDrawn`; the benchmark must not substitute the Home marker for that signal.

### Verification boundary

The AndroidX Compose source confirms that `testTagsAsResourceId` is checked on the tagged semantics node and its ancestors, so the original same-node tag arrangement is valid; no nested-Box workaround is justified. The exact cause of the missing fully-drawn marker remains unresolved. The source now has safer diagnostics to expose the two marker states on the next device run. Static regression checks were updated, but no local Gradle build, APK build, or CI run was started after this change. The currently installed phone bundle does not contain these source corrections.


## Pre-build completeness audit — 2026-11-15 03:xx UTC

Before producing another phone bundle, the current `main-build-testing` workflow, APK build configuration, app manifest, runner, and regression tests were re-inspected.

### Gaps found and corrected

- The phone-runner regression test still required the removed `dumpWindowHierarchy` XML dump and text-redaction implementation. It now asserts that both marker states are checked safely, that the package label is accurate, and that hierarchy serialization/byte buffering are absent.
- The package-manager recovery test previously did not exercise `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. It now injects that failure and verifies that recovery uninstalls only `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile`, never production `com.nuvio.media`.
- The workflow now rejects empty APKs, requires exactly eight APKs, and checks that each R8 benchmark APK contains a non-empty compiled `assets/dexopt/baseline.prof` smaller than 1.5 MiB. This prevents a misleading `BaselineProfileMode.Require` comparison when the packaged profile is absent.
- SHA-256 checksums are now generated relative to the extracted bundle directory and verified in the workflow itself. The bundle count is checked as eight APKs, two scripts, the start-here instructions, and the checksum file.

### Configuration cross-checks

- The app's main manifest contains `<profileable android:shell="true" />`.
- The Baseline Profile Gradle plugin 1.5.0 creates and configures the `benchmarkRelease` and `nonMinifiedRelease` build types for their respective purposes. Its documentation specifies that the benchmark variant is non-debuggable/profileable and minified, while nonMinifiedRelease is deliberately not minified for profile collection. The app script only customizes the isolated package suffix and local debug signing key.
- Both flavors intentionally share `com.nuvio.media.benchmark` and the same temporary debug signing identity within one workflow run. The test runner remains `com.nuvio.android.baselineprofile`.
- The workflow remains manual-only after the one-time build trigger is restored; no persistent push-triggered Android build is intended.

### Remaining verification boundary

The source and workflow configuration are checked remotely, but the updated phone regression suite and APK metadata/profile checks still need to execute in the next authorized GitHub Actions run. No new APK is available until that run completes. The 03:03 phone log still establishes that HomeScreen appeared but the fully-drawn marker was absent; the underlying AppGate readiness cause is not yet known.

References:
- [AndroidX Macrobenchmark setup and target profileability](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [AndroidX Baseline Profile build-type configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)
- [Android Gradle Plugin build variants](https://developer.android.com/build/build-variants)


## Review of the 03:12–03:13 external analysis screenshots

The screenshots correctly distinguish Home route visibility from the app's fully-drawn signal. The phone evidence remains: `nuvio_benchmark_home` appeared, while `nuvio_startup_ready` did not appear within the shared 30-second deadline. The old hierarchy XML dump then threw on U+0000; that was a secondary diagnostics failure and did not identify the original readiness blocker.

The AppGate/MainAppContent source was traced rather than guessing at the cause:

- AppGate forwards readiness only while `gateScreen == Main` and `profileSelectionLoading == false`.
- MainAppContent's `rootContentReady` is `!ownsAppRuntime || (initialHomeReady && !profileSwitchLoading)`.
- `initialHomeReady` is set when the first Home catalog section renders, with a 5-second fallback if catalogs never resolve.
- MainAppContent additionally waits for its launch overlay transition to be idle and hidden before calling `onRootContentReady(true)`.

The precise condition that stayed false on the phone is not yet proven. To make the next device failure diagnostic rather than speculative, test-only Compose tags now expose:
- `nuvio_appgate_main` / `nuvio_appgate_profile_loading` and the current non-main gate state
- `nuvio_main_content_loading`
- `nuvio_launch_overlay_transitioning`
- `nuvio_main_content_ready`

Both instrumentation failure reports now check these states alongside Home and fully-drawn markers. The benchmark still requires both Home and the fully-drawn marker; no readiness gate was relaxed.

### Baseline Profile rule volume

The current generator uses `includeInStartupProfile = true` and `filterPredicate = rule -> true`. That means no package-level filtering is applied to captured rules, although the journey itself only launches the app and waits for its startup readiness. The checked-in text profile is about 1.4 MB. This is a legitimate profile-quality review item, but the text file size is not the Android 1.5 MB limit: official Android documentation applies that limit to the compiled binary `assets/dexopt/baseline.prof`. The updated workflow now checks that binary exists, is non-empty, and is below 1.5 MB for both flavors. We should inspect actual rule composition and compare measured outcomes before adding package filters; blindly restricting to only app classes could discard important Compose/Coil/library startup methods.

### R8 benchmark variant

The concern that `benchmarkRelease` might be unminified is not supported by the current Baseline Profile Gradle plugin configuration. The project uses plugin 1.5.0, which creates and configures `benchmarkRelease` for minified Macrobenchmark and `nonMinifiedRelease` for profile capture. The app module customizes only the isolated application ID suffix and debug signing key. The main manifest also declares `<profileable android:shell="true" />`. Official Android documentation describes the generated benchmark variant as non-debuggable, minified and profileable. The next workflow additionally verifies that a compiled Baseline Profile is actually packaged in each R8 APK.

### Measurement meaning

`StartupTimingMetric` obtains TTID/TTFD from Android startup reporting; `ReportDrawnWhen { appReady.value }` remains the app's TTFD contract. The additional UI Automator wait is a pass/fail guard that refuses to count a run if the Home and fully-drawn markers are absent; it does not replace the metric. This journey measures cold launch through the signed-in Home-ready state, including persisted state, AppGate and app startup work. It is an end-to-end user journey, not a pure Compose or framework initialization benchmark. Results must be labelled accordingly.

### Bundle size and device ABI

The first complete bundle (run 43665349685) built all four APK pairs and uploaded successfully, but was 688,802,693 bytes because each profiling app APK carried native libraries for four ABIs. That is needlessly large for this phone-only workflow. The profiling build now creates arm64-v8a APKs and compiles the Rust native library for arm64 only, while production release builds retain all four ABIs. The phone and desktop runners reject a non-arm64 device rather than allowing a misleading install failure. The user's Galaxy S25+ is arm64-compatible. The next workflow checks the APK's actual native-library ABI before packaging.

### Regression and bundle-integrity checks

- The phone runner regression now exercises an `INSTALL_FAILED_UPDATE_INCOMPATIBLE` scenario and verifies that only `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile` can be removed; production `com.nuvio.media` is never uninstalled.
- The workflow runs the updated phone regression and dual-marker/readiness diagnostic tests before compilation.
- The bundle must contain eight non-empty APKs, two scripts, START-HERE instructions and relative SHA-256 checksums; the workflow verifies the checksum file.
- The workflow remains manual-only. No persistent push trigger is enabled.

### Current verification boundary

The earlier run 43665349685 succeeded, but its 688 MB artifact predates the latest readiness-stage diagnostics, arm64-only packaging, signing-recovery regression assertions and bundle-integrity checks. Do not install that artifact as the final phone bundle. A new authorized workflow run is required to verify the latest source and produce the smaller updated ZIP. The exact AppGate readiness blocker and any startup performance benefit remain unproven until the corrected phone run completes and its output is reviewed.

References:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- https://developer.android.com/topic/performance/baselineprofiles/difference-baseline-startup
- https://developer.android.com/build/configure-apk-splits


### Final regression-fixture correction and current build

The first post-audit CI attempts exposed a test-fixture issue, not an APK compile failure: the signing-recovery test deleted its staged APK directory and then asserted that files from the earlier full/playstore test journeys were still present. The fixture now preserves that staged directory while resetting only its output folder. The package-manager error is captured explicitly so the recovery branch is tested under `set -e`.

The corrected validation step passed in workflow run
[43672203091](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43672203091):
- shell syntax checks passed for both phone and desktop runners
- Home/fully-drawn readiness regression passed
- arm64-only device guard passed in the simulated phone test
- signing-conflict recovery passed, with only benchmark/test packages removed
- existing instrumentation and output-export assertions passed

The same run has proceeded to build the full profile-capture APK pair. Its Play Store profile pair, both R8 benchmark pairs, APK ABI/profile checks, checksum verification and artifact upload are still pending. The workflow is manual-only again; the temporary push trigger was removed immediately after the run started.

## Follow-up: benchmark APKs compiled; bundle assembly stopped before artifact upload — 2026-11-15 04:08 UTC

### Confirmed root cause

Workflow run [43672203091, attempt 2](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43672203091) completed all four Gradle build steps successfully:

- Full profile-capture APK pair — success
- Play Store profile-capture APK pair — success
- Full R8 benchmark APK pair — success
- Play Store R8 benchmark APK pair — success

The packaging step also passed its APK discovery, non-empty file, arm64 ABI, and compiled Baseline Profile checks. It reported 22,236 bytes for the full profile APK and 20,982 bytes for the Play Store profile APK.

The actual failure was an ordering error in the same shell step: `test -s phone-benchmark/START-HERE.txt` ran before the subsequent `cat > phone-benchmark/START-HERE.txt` heredoc created the file. Because the step starts with `set -euo pipefail`, the missing-file test returned non-zero and stopped execution. The checksum generation, bundle-count check, and artifact upload were therefore never reached. This is confirmed by the complete attempt-2 job log; it is not an APK compilation failure or a runner shutdown.

### Source fix and regression guard

- Moved the non-empty `START-HERE.txt` assertion to immediately after the heredoc's closing delimiter.
- Added `scripts/tests/test-android-benchmark-bundle-assembly.sh`. It checks that the instructions file is created before it is validated, that checksum creation and verification happen after all bundle files are created, and that bundle-count/integrity checks precede upload.
- Added this regression to the workflow's pre-build validation step, before Rust setup and Gradle assembly.
- Kept the eight-APK, arm64-only, compiled-profile-size, checksum, and twelve-file bundle requirements unchanged.
- The workflow remains manual-only (`workflow_dispatch`); no persistent push trigger was added.

### Verification and remaining boundary

- `sh -n` passed for the new regression script.
- The regression's ordering logic passed against a representative workflow fixture, including the heredoc delimiter and checksum/upload sequence.
- The actual remote workflow source was checked: START-HERE creation is line 159, its heredoc closes at line 230, the non-empty check is line 231, checksum generation/verification are lines 235–236, bundle-count validation is line 239, and artifact upload begins at line 244. The order is now correct.
- The new regression is wired into pre-build validation. It has not yet run on GitHub Actions against the checked-out repository, and the full bundle has not yet been uploaded. A fresh authorized workflow execution is still required to prove the complete ZIP and artifact upload.
- Context7 documentation retrieval was unavailable because the service reported its monthly quota was exhausted. Exa search returned GitHub's official workflow documentation confirming steps run sequentially and scripts execute through the selected shell. The diagnosis itself is established directly by the exact failed command and the GitHub job log.

## Review of the Grok screenshot and follow-up source audit — 2026-11-15 04:22 UTC

### Fully-drawn readiness: current evidence and correction

The screenshot's central assessment is correct: the benchmark still requires both `nuvio_benchmark_home` and `nuvio_startup_ready`, and the phone evidence has shown Home without the fully-drawn marker. The benchmark must not relax that requirement; `ReportDrawnWhen { appReady.value }` remains the Android TTFD contract.

A suspected controller wiring gap was investigated and discarded. The standard Android entry point calls `App()` with its default `renderMainContent = true`. In that mode, AppGate does not use `externalMainContentReady` from `AppGateController`; its outer launch overlay is disabled and the normal `MainAppContent.onRootContentReady` callback directly feeds `onAppReady` when the gate is Main and profile selection is not loading. The controller-flow observation therefore does not explain the Android phone failure.

The remaining normal-path readiness conditions are explicit in source: `MainAppContent` reports ready only when `initialHomeReady` is true, `profileSwitchLoading` is false, and its own launch-overlay transition is idle and hidden. AppGate additionally gates the callback on `gateScreen == Main` and `profileSelectionLoading == false`. The source has a 5-second fallback that sets `initialHomeReady` if the first Home content callback never arrives, and a 20-second safety timeout for profile-selection loading. These source facts narrow the search but do not prove which condition stayed false during the failed phone launch.

### More discriminating readiness diagnostics

- Added one 1 dp, transparent diagnostic semantics node to `MainAppContent`. Its resource ID encodes both `initialHomeReady` and `profileSwitchLoading` (all four combinations). It does not change either state or any readiness condition.
- Both Baseline Profile collection and Macrobenchmark failure diagnostics now query all four combinations in addition to the existing AppGate, root-content, overlay, Home, and fully-drawn markers.
- Extended `test-android-startup-readiness.sh` to require the new state marker and all four diagnostic queries. The benchmark still waits for Home and fully-drawn markers under the same 30-second shared deadline.
- This is diagnostic instrumentation, not a fix for the readiness failure. A device run is still needed to see which state actually occurs at timeout.

### R8 build-type audit: concern resolved by plugin contract, with artifact checks added

The project pins `androidx.baselineprofile` to 1.5.0. The official AndroidX configuration guide states that the plugin creates `benchmarkRelease` and `nonMinifiedRelease` and overrides incompatible properties: `benchmarkRelease` is non-debuggable, minified and profileable; `nonMinifiedRelease` is non-debuggable, non-minified and profileable. The app module's custom build-type blocks only set the isolated `.benchmark` application ID suffix and temporary debug signing. Therefore, the screenshot's inference that the short custom blocks leave R8 behavior unconfigured is not supported by the pinned plugin contract.

To verify the built outputs rather than trust the variant names, the bundle assembly step now uses the official Android SDK `apkanalyzer` to check each APK's application ID, debuggable flag and `android:profileable android:shell=true` manifest entry. For each `benchmarkRelease` APK it also requires a non-empty AGP R8 `mapping.txt`. The bundle regression test requires these checks to remain in place. These checks have been committed but have not yet run on the new source; no APK build or CI was started for this follow-up.

### Baseline Profile filtering

The generator still uses `filterPredicate = rule -> true`. This means it accepts every rule emitted by the measured startup journey; it does not mean it profiles every possible app path. The checked-in human-readable source profile is 1,409,345 bytes, but the GitHub file-content connector could not return its contents because the blob exceeds its supported response size, so its rule/package composition has not been independently inspected in this pass.

Android's official documentation explicitly says the 1.5 MB limit applies to the compiled binary profile in the APK, not the much larger human-readable text source. The prior failed bundle assembly log measured the compiled profile at 22,236 bytes (full) and 20,982 bytes (Play Store), both well below that limit. No package filter was introduced: filtering to only `com.nuvio` without inspecting the rule set could remove Compose, AndroidX, Coil or other dependency methods that are part of this app's actual startup path. A safe filter decision needs the full rule inventory and, ultimately, measured comparisons.

### Verification boundary and build policy

- Remote source assertions confirm the new state tag, all four diagnostic queries, readiness regression expectations, APK identity/debuggable/profileable checks, R8 mapping-file check, and manual-only workflow configuration are present on `main-build-testing`.
- The current pre-existing Actions run [43675718220](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43675718220) was started earlier from commit `8b0ff930519edb047b98cfd675866d934edd56e8`. It predates the follow-up diagnostic and APK-contract changes and must not be treated as verifying them.
- No new build or CI run was started for this follow-up, in accordance with the user's instruction. The workflow remains `workflow_dispatch` only.
- Context7 retrieval was unavailable due the service's monthly quota. Exa research used official Android documentation for Baseline Profile plugin behavior, Compose test-tag interoperability and `apkanalyzer` command syntax.
- No fully-drawn fix or startup speed improvement is claimed. The next device result must identify the readiness-state combination before any behavioral change is justified.

References:
- https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/tools/apkanalyzer
- https://developer.android.com/develop/ui/compose/testing/interoperability

## Macrobenchmark execution gap and result-integrity hardening — 2026-11-15 04:50 UTC

### Critical source finding

A fresh inspection of the complete StartupBenchmark.java showed that the class contained the shared runColdStartupBenchmark(CompilationMode) helper but no JUnit @Test methods invoking it. As a result, the intended no-compilation versus Baseline Profile comparison was not actually declared as executable Macrobenchmark test cases. A successful build of the APK pair could not establish that the comparison would run or produce measurements.

### Completed improvement cycle 1: declare both benchmark cases

- Added startupNoCompilation() with @Test, calling runColdStartupBenchmark(new CompilationMode.None()).
- Added startupWithBaselineProfile() with @Test, calling runColdStartupBenchmark(new CompilationMode.Partial(BaselineProfileMode.Require)).
- Both use the same existing helper, target package, StartupTimingMetric, cold startup mode, ten measured iterations, Home/fully-drawn gate and timeout. No benchmark readiness requirement was weakened.
- Added scripts/tests/test-android-startup-metric-contract.sh. Its first version was checked against the remote source before the implementation: the two missing test declarations/calls were absent, so the regression correctly identified the gap. The test now requires both JUnit cases, both compilation modes, StartupTimingMetric, cold startup and ten iterations.

### Completed improvement cycle 2: fail closed on missing timing metrics

The desktop phone benchmark runner previously grepped for TTID/TTFD lines when writing the summary but tolerated their absence (|| true). That could leave an apparently completed run without proving that both startup metrics were emitted. The runner now requires both timeToInitialDisplayMs and timeToFullDisplayMs in each flavor's Macrobenchmark log and fails with a specific message if either is missing. The metric-contract regression test now protects this behavior too.

The new metric-contract test is wired into the manual benchmark workflow's preflight validation alongside the phone-runner and readiness tests. The workflow remains manual-only; no build or CI was started for these changes.

### API verification and measurement meaning

- The project pins androidx.benchmark:benchmark-macro-junit4 1.5.0. The official AndroidX API surface exposes public Java constructors for CompilationMode.None() and CompilationMode.Partial(BaselineProfileMode), and BaselineProfileMode.Require is a public enum constant. The Java test uses those exact Java forms.
- Android's StartupTimingMetric reports both timeToInitialDisplayMs (TTID) and timeToFullDisplayMs (TTFD), with minimum, median and maximum. Android recommends focusing on medians when assessing startup changes. TTFD depends on the app reporting fully drawn; the existing Compose ReportDrawnWhen { appReady.value } remains in place.
- The Context7 quota was exhausted when attempting to resolve the pinned libraries, so Context7 documentation could not be retrieved in this pass. Exa research used the official Android Developer Macrobenchmark metric/API pages and the AndroidX Git source API signature. No third-party source was used to determine the Java constructor forms.

### Verification boundary

- Remote source inspection confirms both JUnit test methods now exist and invoke the intended modes.
- Remote source inspection confirms the runner requires both TTID and TTFD log entries for both full and playstore.
- The new static regression test is included in the workflow preflight.
- No build, device benchmark, or CI run was started. The older authorized Actions run [43675718220](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43675718220) was built from commit 8b0ff930519edb047b98cfd675866d934edd56e8, predates these changes, and was still in progress at the last status check with no artifact published. It cannot validate this fix.
- Therefore, the benchmark cases are now declared in source, but compilation and execution remain unverified until a separately authorized run.

References:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule
- https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- https://android.googlesource.com/platform/frameworks/support/+/225853c2fd3977d06ea9c0efaddd35ae1d81e3a6/benchmark/benchmark-macro/api/current.txt

### Follow-up regression execution check — 2026-11-15 04:56 UTC

The metric-contract shell regression was checked with a temporary local fixture: `sh -n` passed, the positive fixture containing both annotated test methods and both metric log guards passed, and a negative fixture with the Baseline Profile test removed failed with the expected targeted message. The test uses `awk` to associate each `@Test` annotation with the following method because POSIX `grep` processes source one line at a time; this avoids a false assumption that a grep expression can match the annotation and method across separate lines.

This fixture check validates the regression script's logic, not Kotlin/Java compilation or the actual Android instrumentation runner. No Gradle task, build, device test or GitHub Actions run was executed.
## Follow-up: regression deduplication and per-flavor fail-fast metrics — 2026-11-15

### Improvement cycle 1 — workflow preflight executes each regression once

The live workflow had two adjacent invocations of `test-android-startup-metric-contract.sh`. This was redundant and made preflight behavior harder to reason about. The metric-contract regression now reads the workflow and requires each of the four source regression scripts (phone runner, bundle assembly, startup readiness and metric contract) to be invoked exactly once. The check was first evaluated against the live workflow and correctly found two invocations on lines 72 and 73. The duplicate line was then removed; the workflow now contains one invocation of each test.

### Improvement cycle 2 — validate startup metrics before running the next flavor

The phone runner previously executed the Full and Play Store Macrobenchmark tasks before checking whether either log actually contained TTID and TTFD. A Full run that omitted `timeToFullDisplayMs` could therefore consume the complete Play Store run before failing.

- Added `require_startup_metrics(label)`, which first requires a non-empty log and then requires both `timeToInitialDisplayMs` and `timeToFullDisplayMs`.
- The Full validation now runs immediately after the Full connected Macrobenchmark task, before Play Store starts.
- The Play Store validation runs immediately after the Play Store task.
- The old deferred two-flavor metric-validation loop was removed. The later summary loop remains only to copy useful benchmark lines into `summary.txt`.
- Extended the metric-contract regression to verify the helper and the precise order of both run/check pairs.

### Verification

- The new duplicate-invocation assertion correctly failed against the previous workflow state (two matching commands) and passes after the duplicate was removed.
- The fail-fast assertion correctly failed against the previous runner (no per-flavor validation helper) and the remote source now shows Full run/check at lines 101/105 and Play Store run/check at lines 106/110.
- Local shell fixtures passed the positive case and rejected two negative cases: duplicate workflow invocation and deferred validation after the Play Store task.
- Remote source checks confirm both JUnit benchmark methods remain declared, both compilation modes remain intact, TTID/TTFD checks remain required, and all four preflight regression scripts are invoked once.
- Exa's official Android Developers results confirm the exact `StartupTimingMetric` keys (`timeToInitialDisplayMs` and `timeToFullDisplayMs`) and the min/median/max result fields. Android recommends using the median when comparing startup performance. TTFD is tied to the app's `reportFullyDrawn` signal. References: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics and https://developer.android.com/reference/androidx/benchmark/macro/StartupTimingMetric.
- The Context7 `Main` link was explicitly attempted but rejected by the connector as an ineligible linked account. No account switch or Context7 documentation retrieval was successful. These changes are shell/workflow changes; the API metric names were verified against the official Android documentation through Exa.
- No Gradle build, APK build, device benchmark, or GitHub Actions run was started. The older run [43675718220](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43675718220) remains associated with earlier commit `8b0ff930519edb047b98cfd675866d934edd56e8`; its last observed state was still in progress during the Play Store R8 build and it had no uploaded artifact. It does not validate the current branch changes.

### Remaining verification boundary

The checks above validate source and shell control-flow fixtures. They do not prove that Gradle's instrumentation output on a physical phone emits both metric keys or that the Java test class compiles on the actual AGP/Benchmark toolchain. That still requires a separately authorized build and device run.

## Startup benchmark result completeness — 2026-11-15

### Improvement cycle 1 — require both JUnit comparison cases to execute

The LADB runner previously treated a successful `am instrument` exit as sufficient evidence that the cold-start comparison had completed. A zero exit and a generated file do not prove that both JUnit comparison methods ran.

- Added a fail-closed check for the AndroidJUnitRunner status records of `startupNoCompilation` and `startupWithBaselineProfile`.
- The check runs only for Macrobenchmark instrumentation, after the generic instrumentation failure checks and before copying results.
- The phone-runner regression fixture now emits the expected per-test records and verifies that omitting either comparison arm causes a targeted failure.
- AndroidX Test's `InstrumentationResultPrinter` implementation confirms that it sends each JUnit method name in the status Bundle under the test key, along with its class name. The raw `am instrument -r` output exposes these as `INSTRUMENTATION_STATUS: test=...` records.

### Improvement cycle 2 — reject incomplete Macrobenchmark JSON

The old phone-runner fixture wrote `{"benchmarks":[]}` and the test only required a file to exist. That meant an empty result could satisfy the output-export contract.

- The runner now checks the copied `*benchmarkData.json` result file(s) before declaring Macrobenchmark complete.
- It requires at least one result named `startupNoCompilation` and one named `startupWithBaselineProfile`.
- It also requires at least two occurrences of each metric key, `timeToInitialDisplayMs` and `timeToFullDisplayMs`, so the file contains the two metrics for both comparison results.
- The fixture now contains a representative two-result JSON payload and negative cases for empty JSON, a missing profile result, missing TTFD, and metrics present for only one arm.
- The logic uses the Android shell's existing `awk` and `grep` commands; no runtime package or new dependency was introduced.

### API and source verification

- Context7 was successfully used through the connected `Main` account for AndroidX (`/androidx/androidx`) and AndroidX Test (`/android/android-test`). The AndroidX Macrobenchmark source represents each JSON record with `className`, `name` and `metrics`; `StartupTimingMetric` uses the exact TTID/TTFD keys checked above.
- AndroidX Test's current `InstrumentationResultPrinter` source confirms per-test `class` and `test` status fields. The AndroidX Benchmark 1.5.0 source query also confirmed that a Macrobenchmark test is skipped when `androidx.benchmark.enabledRules` does not include `macrobenchmark`; this runner passes that rule explicitly.
- Exa's official Android Developers results confirm that the startup metrics include TTID and TTFD, that the results report minimum/median/maximum, and that median values should be used to compare typical startup times. A physical-device run is still required for actual Nuvio measurements.

### Verification boundary

- The new regression fixture was first exercised against the previous “exit code only” behavior and failed because an instrumentation log missing the profile arm was accepted. After adding the guards, the isolated POSIX-shell fixture passed the complete and incomplete log/JSON cases, including JSON fields with whitespace around separators; `sh -n` also passed for that isolated harness.
- GitHub source reads confirm the regression fixture and runner changes are present on `main-build-testing`. The complete repository shell regression suite was not run locally: the container could not resolve `github.com` for a shallow checkout, and no build or CI execution was authorized.
- No Gradle task, APK build, device instrumentation run, Macrobenchmark, or GitHub Actions workflow was started.
- This improves result integrity only. No startup speedup, TTID/TTFD improvement, or successful phone benchmark is claimed until a new authorized device run produces valid measurements.

References:
- https://github.com/android/android-test/blob/main/runner/android_junit_runner/java/androidx/test/internal/runner/listener/InstrumentationResultPrinter.java
- https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile

## Benchmark evidence quality improvements — 2026-11-15

### Improvement cycle 1 — validate every measured startup sample

The phone runner previously checked that the two result names and TTID/TTFD keys appeared in `benchmarkData.json`, but did not verify the actual sample arrays or repeat count. An incomplete JSON result could therefore contain both metric names while one metric had fewer than the ten requested iteration samples.

The runner now parses the AndroidX Macrobenchmark result shape using the Android shell's existing POSIX `awk`:

- Requires exactly one `startupNoCompilation` and one `startupWithBaselineProfile` record, both under `com.nuvio.android.baselineprofile.StartupBenchmark`.
- Requires `repeatIterations=10` on both records.
- Requires exactly two metric objects for each of `timeToInitialDisplayMs` and `timeToFullDisplayMs`.
- Requires each metric to include exactly ten positive numeric `runs` samples.
- Requires positive `minimum`, `median`, and `maximum` values with `minimum <= median <= maximum`.
- Checks every sample is within the reported range and that the sample extrema equal the reported minimum and maximum.

The fake Macrobenchmark output was upgraded from an empty results array to a realistic two-result JSON fixture. Negative cases now cover empty/missing result data, wrong repeat count, nine samples, missing sample arrays, non-numeric samples, and inconsistent statistics.

### Improvement cycle 2 — retain device conditions around the measurement

Each Macrobenchmark run now writes `environment-before.txt` and `environment-after.txt` in its run directory. These snapshots capture the model and API level; Android battery status, charge/power source, level, voltage and temperature; the first 80 lines of the thermal service report; battery-saver state; and display brightness mode/value.

This is diagnostic-only. The runner does not modify brightness, power mode, thermal state, network state, CPU frequencies, or app state. Snapshot collection is best-effort: unavailable Android diagnostic commands are recorded as unavailable, and failure to create a snapshot file is reported to stderr and returns successfully so it cannot replace a successful or failed instrumentation result. The regression fixture now models both normal device readings and diagnostics that return errors.

### Context7 and Exa findings applied

- Context7 retrieved AndroidX Benchmark JSON source and confirmed the `BenchmarkData.TestResult` shape: `name`, `className`, `repeatIterations`, and a metrics map. Its single-metric result includes `minimum`, `maximum`, `median`, and the per-iteration `runs` array.
- Context7 also retrieved the Macrobenchmark Java API overload including a separate setup block and measure block, and the Baseline Profile collection API's stability and rule-filter parameters. No app journey or profile collection API was changed in this pass.
- Exa's official Android Benchmark-in-CI documentation independently shows the JSON device-context and result schema, including `repeatIterations`, metric runs and summary values.
- Exa's official Android environment guidance recommends a charged device, no competing background apps, stable network conditions when networking is measured, fixed display brightness and stable temperature. The new snapshots preserve several of those conditions for later interpretation; they do not enforce them.

References:
- https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt
- https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt
- https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci
- https://developer.android.com/topic/performance/appstartup/setup-env
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics

### Verification boundary

- The AWK validator was executed locally against a representative two-result AndroidX JSON fixture. The complete fixture passed; a whitespace-formatted fixture also passed. Separate negative fixtures were rejected for a short sample array, wrong repeat count, inconsistent minimum/median/maximum, missing runs, and non-numeric samples.
- The device-snapshot helper was exercised locally in an isolated POSIX shell with stubbed `getprop`, `dumpsys`, and `settings` commands. Normal battery/thermal readings were retained, and simulated diagnostic-command failures still produced snapshots without stopping the helper.
- The same helper was also exercised with a snapshot path that could not be created; it emitted the write diagnostic and returned success rather than aborting the caller.
- The remote phone-runner test fixture and metric-contract script now cover these cases. The complete repository regression script was not run end-to-end in this environment because the container could not resolve GitHub for a checkout. Static remote source checks and the isolated fixture checks are not a substitute for running that full script on the branch.
- No Gradle task, APK build, device instrumentation run, Macrobenchmark, or GitHub Actions workflow was started.
- The work improves measurement integrity and diagnostic context only. No Nuvio startup improvement, profile speedup, or statistical confidence claim is made. A new authorized physical-device run is still required to collect real TTID/TTFD data.

## Generated Baseline Profile content validation — 2026-11-15

### Improvement cycle 1 — phone collection cannot accept an empty profile

The phone runner previously considered profile collection complete when it copied any matching profile text file. That could accept a zero-byte `baseline-prof` file (or a whitespace-only file) as a successful collection.

- Added a post-copy validation step for `*baseline-prof*.txt` files.
- At least one `baseline-prof` file must be present.
- Every copied `baseline-prof` file must be non-empty and contain at least one non-whitespace character.
- `startup-prof` remains optional; the check does not incorrectly require a Startup Profile on every supported API level.
- The phone-runner fixture now simulates valid, empty and whitespace-only baseline profile outputs for both Full and Play Store variants. Empty and whitespace-only results must fail with a specific diagnostic before the runner reports collection complete.

### Improvement cycle 2 — desktop collection checks the same source artifact

The desktop runner previously checked only that the generated-profiles listing contained a filename called `baseline-prof.txt`. A zero-byte or whitespace-only text file could therefore pass the profile preflight and proceed to the A/B benchmark.

- It now resolves the actual `baseline-prof.txt` path in the expected `src/<flavor>NonMinifiedRelease/generated/baselineProfiles` folder.
- It rejects missing, zero-byte and whitespace-only profile content before starting either flavor's Macrobenchmark.
- The existing source regression now guards the explicit path resolution, non-zero file check and non-whitespace content check.

### Source basis

- AndroidX Baseline Profile's producer `CollectBaselineProfileTask` collects the profile artifacts from instrumentation results and copies matching human-readable `*-baseline-prof-*.txt` / `*-startup-prof-*.txt` outputs. It does not itself reject an empty baseline-profile text file.
- AndroidX's Baseline Profile Gradle plugin enables the `noBaselineProfileRulesGenerated` warning by default, but that warning is not a replacement for the phone/desktop runner's own fail-closed acceptance check.
- Android's Baseline Profile documentation distinguishes human-readable source rules from the compiled binary `assets/dexopt/baseline.prof` consumed by ART. A file's presence alone is not proof that any rules were collected.
- The existing workflow still independently checks that each benchmark APK contains a non-empty compiled `assets/dexopt/baseline.prof` below the documented 1.5 MiB limit. This new check closes the earlier source-profile hand-off gap; it does not replace the APK-level check.

References:
- https://github.com/androidx/androidx/blob/e70f5406a4fa24bd3c2dfb27832ab5fa87c9c5c6/benchmark/baseline-profile-gradle-plugin/src/main/kotlin/androidx/baselineprofile/gradle/producer/tasks/CollectBaselineProfileTask.kt
- https://github.com/androidx/androidx/blob/e70f5406a4fa24bd3c2dfb27832ab5fa87c9c5c6/benchmark/baseline-profile-gradle-plugin/src/main/kotlin/androidx/baselineprofile/gradle/utils/WarningsExtension.kt
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles

### Verification boundary

- A focused local POSIX-shell fixture accepted one non-empty baseline rule and rejected a zero-byte and a whitespace-only profile with the intended diagnostic.
- The desktop preflight assertions were checked against the added path, file-size and non-whitespace guards; the isolated assertion script passed `sh -n`.
- The phone regression fixture now contains the full two-flavor negative cases, but the repository's complete `test-nuvio-phone-benchmark.sh` was not executed end-to-end here because the repository checkout is unavailable in the local shell environment. The GitHub file reads confirm the updated fixture and runner are present.
- No Gradle build, APK build, device instrumentation, profile generation, Macrobenchmark, GitHub Actions workflow or CI run was started.
- This change improves profile-capture evidence integrity only. It does not claim the new phone-collected candidate rules are packaged into the existing APK or that startup speed improved.

## Full benchmark-path audit — readiness and variant hand-off — 2026-11-15

### TTFD/readiness signal cross-check

The existing measurement signal agrees with the app's readiness state:

- `MainActivity` registers `ReportDrawnWhen { appReady.value }`; this is the Android fully-drawn/TTFD signal.
- `AppGate` forwards `onAppReady` only when the main gate is active and profile/launch overlays are idle and hidden.
- `MainAppContent` computes root readiness from initial Home readiness and profile-switch loading; it reports ready only when root content is ready and the launch overlay has completed and is hidden.
- `HomeScreen` exposes `nuvio_benchmark_home` on its root content container.
- Both `StartupBenchmark` and `BaselineProfileGenerator` wait for the HomeScreen marker and then for `nuvio_startup_ready`, with a shared 30-second deadline.

This means the benchmark is not treating splash/first composition as full readiness: TTID is the first frame, while the TTFD signal is held until the Home route and startup transition have completed. I found no concrete defect in this path and made no app/Compose changes.

### Baseline Profile variant hand-off cross-check

The repository configuration and runner steps remain consistent:

- The app's `benchmarkRelease` and `nonMinifiedRelease` variants use the isolated `com.nuvio.media.benchmark` application ID and the same signing configuration, so the phone can move between collection and measurement variants without disturbing production `com.nuvio.media`.
- The app config uses per-variant generated profiles (`mergeIntoMain = false`), saves them to source (`saveInSrc = true`) and disables implicit regeneration during ordinary release assembly (`automaticGenerationDuringBuild = false`).
- The desktop runner generates the Full and Play Store `nonMinifiedRelease` profile tasks, then benchmarks their matching `benchmarkRelease` variants.
- The phone-only workflow packages both nonMinifiedRelease and benchmarkRelease APK pairs. The phone runner's collection step explicitly states that the newly collected text candidate is not injected into the already-built benchmark APK; a later authorized build is required to benchmark a newly collected profile.
- The workflow independently checks the optimized benchmark APK for a non-empty `assets/dexopt/baseline.prof` below 1.5 MiB. The new text-profile check complements rather than replaces this compiled-artifact check.

### Version and environmental research

- The pinned repository dependencies include AndroidX Benchmark 1.5.0, ProfileInstaller 1.4.1 and Android Gradle Plugin 9.4.0. Context7 returned the Macrobenchmark setup/measure API and AndroidX benchmark metric/result model; Exa cross-checked Android's official Macrobenchmark, Baseline Profile and environment guidance.
- AndroidX Macrobenchmark's cold-start path uses its setup block before process termination, clears shader/page caches as supported by the library/device, then measures the start block. The existing test puts `pressHome()` in setup and `startActivityAndWait()` plus readiness waiting in the measure block. No redundant launch or pre-warm step was found.
- The phone diagnostic snapshots remain observational only. No brightness, battery, power, thermal, CPU-clock or network settings are changed automatically.

### Audit conclusion

The concrete change in this audit is limited to rejecting empty/whitespace Baseline Profile source artifacts in both collection paths. Other reviewed paths—the app-ready/TTFD signal, cold-start block separation, variant package/signing arrangement, profile source variant wiring and compiled APK profile check—were left unchanged because repository code and source documentation did not show a defect.

No Gradle build, APK build, profile generation, device benchmark, GitHub Actions workflow or CI run was started.
