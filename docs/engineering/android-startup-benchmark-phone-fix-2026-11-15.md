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

## Further benchmark integrity cycles — 2026-11-15

### Cycle 1 — lock the build workflow to explicit manual dispatch

A history review found that commit `8b0ff930519edb047b98cfd675866d934edd56e8` temporarily added a `push` trigger for `main-build-testing` while leaving a comment that described the workflow as manual-only. The benchmark workflow was subsequently restored to `workflow_dispatch` only; the current file at this checkpoint contains no automatic `push` or pull-request trigger.

- Added a regression guard to `scripts/tests/test-android-benchmark-bundle-assembly.sh`. It inspects only the workflow's top-level `on:` keys and rejects every trigger other than `workflow_dispatch`.
- This protects the documented no-automatic-build contract from future accidental trigger reintroduction.
- The old Actions history records push-triggered runs 43672203091 and 43675718220 on earlier SHAs. Run 43672203091 failed during `Assemble phone-ready benchmark bundle`; the upload was skipped. The available run summary for 43675718220 reports job failure but did not expose the failing step/log, so its specific cause is not asserted here.
- A focused local AWK check rejected a workflow fixture with `push` plus `workflow_dispatch` and accepted the current manual-only trigger shape.

### Cycle 2 — cross-check reported medians against the raw samples

The phone runner already required exactly ten positive samples and verified that reported minimum/maximum matched their extrema. It did not verify that the reported median was calculated from those samples: any in-range median could pass.

- Added a `wrong-median` fixture to `scripts/tests/test-nuvio-phone-benchmark.sh`. The fixture changes a valid TTID median from 445 to 446 while leaving min=400, max=490 and all ten raw runs unchanged. The validator must reject it.
- Updated `validate_macrobenchmark_statistics()` in `scripts/nuvio-phone-benchmark.sh` to sort the raw sample array and recompute the AndroidX 50th percentile using the same interpolation rule: `idealIndex = 0.5 * (sampleCount - 1)`, then linearly interpolate between the adjacent sorted values.
- Added a small relative tolerance (`1e-6 * (1 + expectedMedian)`) to avoid rejecting harmless JSON floating-point serialization differences.
- Extended `scripts/tests/test-android-startup-metric-contract.sh` to require this recomputation and mismatch rejection.

### Source basis

- The repository pins `androidx.benchmark:benchmark-macro-junit4:1.5.0`, `androidx.profileinstaller:profileinstaller:1.4.1` and Android Gradle Plugin 9.4.0.
- AndroidX `MetricResult.kt` computes `median` using `getPercentile(sortedValues, 50)`; `getPercentile` uses `percentile / 100.0 * (size - 1)` and interpolates between adjacent values. The file at AndroidX commit `b7a21ae178189e339dd750865f86fae3219f0101` (4 September 2026, before the 1.5.0 release on 9 September 2026) contains the implementation used for this validation.
- AndroidX `BenchmarkData.SingleMetricResult` stores `minimum`, `maximum`, `median`, `coefficientOfVariation` and `runs`, confirming that the validator has the raw data needed to check the reported median.
- AndroidX Macrobenchmark's cold-start implementation runs setup before killing the app process, drops shader cache and app page cache as supported, verifies the process is no longer alive, then runs the measured block. The existing Nuvio test's `pressHome()` setup and launch/readiness measure block match that design.
- `CompilationMode.Partial(BaselineProfileMode.Require)` requires a usable Baseline Profile and ProfileInstaller; Android's documentation says it throws when the profile or installer is missing. The existing APK packaging check plus this required compilation mode remain the appropriate evidence that the measured arm is actually profile-enabled.
- AndroidX's Macrobenchmark JSON constructor currently sets `thermalThrottleSleepSeconds = 0` for Macrobenchmark results, so it is not treated as a thermal-throttling signal. The phone runner retains the before/after thermal-service snapshots; I did not introduce a speculative thermal threshold or root-only clock control.

References:
- https://github.com/androidx/androidx/blob/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/MetricResult.kt
- https://github.com/androidx/androidx/blob/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt
- https://github.com/androidx/androidx/blob/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- https://developer.android.com/jetpack/androidx/releases/benchmark

### Verification boundary

- The manual-trigger policy was exercised against a representative historical `push` trigger shape and the current `workflow_dispatch`-only shape: the former was rejected and the latter accepted.
- The median-validation logic was exercised with valid integer samples, valid decimal samples in unsorted iteration order, and an in-range but incorrect median: valid medians were accepted and the inconsistent median rejected.
- The new repository regression fixture includes both Full and Play Store variants. The complete `test-nuvio-phone-benchmark.sh` and `test-android-benchmark-bundle-assembly.sh` were not run end-to-end against a local checkout in this session; their updated remote source and focused helper behavior were inspected.
- No Gradle task, APK build, device instrumentation, profile generation, Macrobenchmark, Actions dispatch or CI run was started.
- These cycles strengthen automation safety and result integrity only. No Nuvio startup speedup or profile performance gain is claimed.
### Final workflow boundary and remote review

- Reviewed all 12 workflow files under `.github/workflows/` at the final branch head. The benchmark workflow is `workflow_dispatch` only. The Android build-bearing PR workflow is pull-request-triggered; iOS test and mobile release workflows are manual-dispatch. The remaining workflows are issue, schedule, release, workflow-run or workflow-call automation. No workflow in the current branch declares a `push` trigger.
- The benchmark pre-build validation step invokes the phone runner regression, bundle-assembly regression, startup-readiness regression and metric-contract regression before the first `Build full profile-capture APK pair` Gradle step.
- The remote comparison from checkpoint `629e71e26763a5e738f08355a58f82c57d97c52e` shows six changed paths: the phone runner, three source-regression scripts and two engineering notes. The branch remains zero commits behind; later commits in this review updated documentation only. No app runtime, Gradle build, Android dependency or workflow definition changed.
- The latest Actions run returned for `main-build-testing` was created at `2026-11-15T04:12:13Z`, before the follow-up commits. No new workflow run was started by this session; no build, dispatch or CI command was invoked.
- The static review was performed against the live remote source. No independent reviewer tool was available, and the full repository shell regression scripts were not run against a local checkout.

## Phone output isolation and historical bundle failure diagnosis — 2026-11-15

### Confirmed output-directory ownership gap

The phone runner previously searched the entire `/sdcard/Android/media` tree for newly written `baseline-prof*.txt`, `startup-prof*.txt`, `*benchmarkData.json` and trace files. That was broader than the AndroidX output contract.

- The project pins `androidx-benchmark` 1.5.0 in `gradle/libs.versions.toml`.
- The `:baselineProfile` test module sets `android.experimental.self-instrumenting=true` and its package/namespace is `com.nuvio.android.baselineprofile`.
- AndroidX Benchmark's `Outputs` implementation obtains `InstrumentationRegistry.getInstrumentation().targetContext`, then uses that context's first mounted `externalMediaDirs` directory on API 29+. For a self-instrumenting test APK, the instrumentation target context belongs to the test APK, not the measured Nuvio app.
- The expected output subtree for this runner is therefore `/sdcard/Android/media/com.nuvio.android.baselineprofile`. It must not collect generic similarly named files belonging to any other installed app.

The phone runner now forms `TEST_MEDIA_ROOT` from the configured Android media root plus `TEST_PACKAGE`, then uses that path for both profile and Macrobenchmark/trace discovery. The existing `NUVIO_PHONE_MEDIA_ROOT` override remains the media-root override; it is not the per-package directory. App IDs, test IDs, output filenames, instrumentation arguments and benchmark methodology are unchanged.

### Regression fixture and focused verification

The phone-runner fixture now stores valid outputs under the self-instrumenting package subtree and, on an isolation-focused run, creates two foreign-app decoys after the run marker:

- An empty `foreign-baseline-prof-decoy.txt`, which the former broad scan copied and then incorrectly rejected alongside a valid Nuvio profile.
- An incomplete `foreign-benchmarkData.json`, which the former broad scan copied and treated as a Nuvio result file.

The fixture checks that no foreign-app path appears in any copied-files manifest. The focused local shell reproduction confirmed the old scan copied both Nuvio and foreign files; the package-scoped scan copied only the Nuvio files, allowing the valid profile through its non-whitespace check. The remote runner/test sources were then re-fetched and inspected at the new commit.

The full `test-nuvio-phone-benchmark.sh` was not run end-to-end against a local checkout in this environment. The local reproduction validates the exact directory-selection behavior, not the entire script. No Gradle task, APK build, device instrumentation, profile generation, Macrobenchmark, Actions dispatch or CI run was started.

### Historical GitHub Actions failure root cause

The logs for historical Actions run [43672203091](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43672203091), job `132689544532`, establish the failing order in the `Assemble phone-ready benchmark bundle` step:

1. The step ran `test -s phone-benchmark/START-HERE.txt`.
2. The next command was the heredoc that created `phone-benchmark/START-HERE.txt`.
3. The file did not exist yet, so the pre-creation non-empty check returned non-zero under the step's fail-fast shell and stopped bundle assembly.
4. All four profile-capture/R8 APK assembly steps preceding this bundle step had completed successfully; artifact upload was skipped because assembly stopped.

The current `test-android-benchmark-bundle-assembly.sh` regression explicitly requires the heredoc closing delimiter to occur before the non-empty check, then requires checksum creation/verification and bundle-count validation before upload. This confirms the known ordering defect is guarded in the current branch.

A separate failed run, [43675718220](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43675718220), has no retrievable job-log blob in the available GitHub response. Its specific failure cause remains unknown; no root cause is inferred from the red conclusion alone.

### Source references

- [AndroidX Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark)
- [AndroidX Benchmark Outputs.kt at the reviewed AndroidX source revision](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/Outputs.kt)
- [Android Developers: Benchmark in Continuous Integration — save and locate files](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
- [AndroidX Macrobenchmark BaselineProfiles.kt at the reviewed source revision](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/BaselineProfiles.kt)

No startup performance improvement is claimed. The changes make phone-side artifact collection more deterministic and prevent unrelated app outputs from contaminating the recorded run.


## Macrobenchmark trace completeness validation — 2026-11-15

### Improvement cycle 1 — validate AndroidX trace references in result JSON

The phone runner previously validated the two startup result names, both TTID/TTFD metrics, ten raw timing samples per metric, extrema and the AndroidX median. It did not validate the profilerOutputs trace references in those same result records.

AndroidX Benchmark 1.5.0 records trace references on each BenchmarkData.TestResult. Its Macrobenchmark implementation converts each measured iteration trace path into a ProfilerOutput with type PerfettoTrace, label Trace Iteration <index>, and a filename relative to the benchmark output directory. The Nuvio class has two measured JUnit methods, each configured for ten cold-start iterations. Accordingly, valid phone output must contain two profilerOutputs arrays, each with ten Perfetto entries and exactly one label for every iteration index from 0 through 9.

The AWK result validator now rejects a result when either profilerOutputs array is absent, either array has fewer than or more than ten PerfettoTrace entries, a filename is missing, or an iteration label is missing or duplicated. The new missing-trace-metadata fixture keeps all timing statistics intact but removes one trace reference from the second comparison arm, proving that valid timings alone are no longer sufficient.

### Improvement cycle 2 — verify the copied trace artifacts

A JSON filename reference is useful only if the corresponding trace is actually available for review. The phone runner now extracts all twenty filenames from the two result arrays and checks that:

- there are exactly twenty references and all are unique;
- every reference is a local filename, not a path outside the run folder;
- every referenced file exists in the run folder and is non-empty; and
- exactly twenty non-empty trace files were copied.

The file scan recognizes both .trace and .perfetto-trace. AndroidX Macrobenchmark can emit .perfetto-trace files; checking only the .trace suffix would incorrectly reject valid output. The regression fixture now creates twenty synthetic non-empty .perfetto-trace files for a valid run, and separate negative cases remove one referenced file (missing-trace-file) or leave one referenced file zero bytes (empty-trace-file).

### Source basis

- AndroidX Benchmark 1.5.0 Macrobenchmark.kt collects per-measurement-iteration trace paths and creates a Profiler.ResultFile.ofPerfettoTrace for each path, with Trace Iteration <index> labels, before placing the outputs in the BenchmarkData.TestResult.
- AndroidX BenchmarkData.kt defines TestResult.profilerOutputs and the ProfilerOutput fields type, label, and filename; its trace enum contains PerfettoTrace.
- AndroidX Profiler.kt defines ofPerfettoTrace and derives the JSON filename using Outputs.relativePathFor.
- Android's official Benchmark-in-CI documentation states that Macrobenchmark emits one Perfetto trace file per measured iteration and that trace files (.trace or .perfetto-trace) are stored alongside the benchmark JSON output.
- The runner's two startup JUnit methods each request ten iterations, so the expected total is twenty traces: ten for startupNoCompilation and ten for startupWithBaselineProfile.

References:
- [AndroidX Macrobenchmark.kt at the reviewed 1.5.0-era source revision](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt)
- [AndroidX BenchmarkData.kt](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt)
- [AndroidX Profiler.kt](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/Profiler.kt)
- [Android Developers: Benchmark in Continuous Integration](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)

### Verification boundary

- Focused local AWK fixtures accepted two arrays containing ten trace references each, then rejected a fixture with one reference removed.
- A focused local shell reproduction accepted twenty unique, non-empty .perfetto-trace artifacts and rejected both a missing referenced file and a zero-byte referenced file. The reproduction also confirmed that .perfetto-trace must be included explicitly in the file glob.
- The updated remote phone-runner fixture covers valid trace references, missing trace metadata, missing physical trace output, and empty trace output for both Full and Play Store variants. The full test-nuvio-phone-benchmark.sh was not executed end-to-end against a local checkout in this environment; the focused helper checks and remote source inspection are not a substitute for that full suite.
- No Gradle task, APK build, profile generation, device instrumentation, physical-device Macrobenchmark, workflow dispatch or CI run was started.
- This change makes the timing report and trace evidence fail together when the trace set is incomplete. It does not claim any Nuvio startup speedup or change the measured startup path.


## Final remote verification — 2026-11-15 16:xx UTC

- Final branch: main-build-testing
- Final HEAD: 7414622c477599f169bc5480dfdc560ebab6ca19 (fix(benchmark): preserve JSON record boundaries in trace extraction)
- Comparison from the trace-audit starting checkpoint ad96e564ea8b82a4d64f7de83fb9a5edae13fe63: 5 commits ahead, 0 behind; four changed paths comprise the phone runner, its regression script, and the two existing engineering notes.
- Comparison from the earlier benchmark checkpoint 629e71e26763a5e738f08355a58f82c57d97c52e: 17 commits ahead, 0 behind; the same six benchmark paths previously audited remain the only changed paths.
- The benchmark workflow remains workflow_dispatch-only. The latest returned Actions runs are historical runs on earlier SHAs; no Actions run references the final HEAD.
- The focused shell/AWK reproductions described above were run locally. The complete repository phone-runner test script was not run end-to-end because a local checkout was unavailable.
- No Gradle build, APK build, device test, physical-device benchmark, workflow dispatch or CI run was started. No startup performance improvement is claimed.


## Follow-up audit — instrumentation result semantics and output traceability (2026-11-15)

### Cycle 1 — distinguish instrumentation completion from test failure

**Confirmed defect**

- The phone runner previously failed when its instrumentation log contained any negative `INSTRUMENTATION_CODE`.
- This conflated the overall instrumentation Activity result with an individual test result. Android's `Activity.RESULT_OK` is `-1`; AOSP's `InstrumentationTestRunner` and AndroidX `AndroidJUnitRunner` finish using `Activity.RESULT_OK`. The standard final line is therefore `INSTRUMENTATION_CODE: -1`, including for a normal completed run. AOSP's `am instrument` examples show the same final `-1` in both successful and failing test output; test failure is carried in the test summary/status events, not that final Activity result.
- Consequently, the previous expression could classify a successful Baseline Profile collection or Macrobenchmark invocation as a failure after all test methods had completed.

**Change**

- Removed only the `INSTRUMENTATION_CODE: -[0-9]` alternative from the phone runner's failure expression.
- Kept the shell command exit-code check, `FAILURES!!!`, `INSTRUMENTATION_FAILED`, and negative `INSTRUMENTATION_STATUS_CODE` detection. Negative per-test statuses remain failures/skips and are not confused with the final Activity result.
- Extended the fake instrumentation output for both Baseline Profile and Macrobenchmark to include the normal `INSTRUMENTATION_CODE: -1` line. Added a negative per-test status fixture to ensure actual test failure is still rejected.

**Primary source references**

- AOSP `InstrumentationTestRunner`: https://android.googlesource.com/platform/frameworks/base/+/master/test-runner/src/android/test/InstrumentationTestRunner.java — calls `finish(Activity.RESULT_OK, ...)`; its documented status values distinguish per-test status from the overall finish result.
- AndroidX `AndroidJUnitRunner`: https://github.com/android/android-test/blob/master/runner/android_junit_runner/java/androidx/test/runner/AndroidJUnitRunner.java — finishes with `Activity.RESULT_OK`.
- Android `Activity` API reference: https://developer.android.com/reference/android/app/Activity#RESULT_OK — defines the successful result constant.
- The legacy AOSP instrumentation testing example also displays `INSTRUMENTATION_CODE: -1` after both a passing test run and a run containing a test failure: https://www.kandroid.org/online-pdk/guide/instrumentation_testing.html.

### Cycle 2 — label combined-run measurement artifacts as measurements

**Confirmed defect**

- In the combined `run <flavor>` flow, the runner collects the profile, sets `ACTION=install`, installs the benchmark APK pair, and immediately invokes Macrobenchmark.
- `new_run_dir()` names its output directory from `ACTION`. The Macrobenchmark files from the combined workflow were therefore written to `install-<timestamp>`, even though they are measurement results. This was a misleading artifact label, not a change in the benchmark measurement itself.

**Change**

- Left `ACTION=install` in place while installing the BenchmarkRelease pair and writing the `install:<flavor>` state marker.
- Set `ACTION=measure` immediately after installation and before invoking Macrobenchmark. The combined run now stores its JSON, traces, instrumentation log and environment snapshots under `measure-<timestamp>`.
- Added a regression assertion that the combined workflow's `measure-*` directory contains the copied `benchmarkData.json`.

### Re-check of the phone evidence and benchmark boundary

- The available `current-install.txt` attachment contains only `install-profile:playstore`. It identifies the installed profile-capture variant; it is not a Macrobenchmark result and does not prove that either startup test has run.
- No `benchmarkData.json`, `instrumentation.log`, Perfetto trace, or before/after device snapshot was present in the accessible upload. Therefore this audit cannot calculate or claim a startup-time improvement or diagnose a measured TTID/TTFD regression.
- The current runner's separate app and instrumentation package identities remain intentional: the app under test is `com.nuvio.media.benchmark`; the self-instrumenting test package is `com.nuvio.android.baselineprofile`. The Play Store production package `com.nuvio.media` remains outside the installation/recovery path.
- AndroidX Benchmark source inspection confirmed that result data is written through `ResultWriter`, that `BenchmarkData.TestResult` contains `profilerOutputs` with trace filenames, and that its output directory is derived from the instrumentation target context's external media directory. This supports retaining the current test-package-scoped output scan; no speculative package/path change was made.
- AndroidX source references: `ResultWriter.kt`, `Outputs.kt`, `BenchmarkData.kt` and `Macrobenchmark.kt` in https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/.

### Verification and integration record

- RED reproduction: the previous failure expression matched a normal log containing `INSTRUMENTATION_CODE: -1`; the combined-run state produced an `install-*` directory label.
- GREEN focused POSIX-shell reproduction: the corrected expression accepted final code `-1`, rejected negative per-test status `-2`, and rejected `INSTRUMENTATION_FAILED`; the measurement directory label was `measure-*`.
- The remote regression fixture was updated to cover successful final instrumentation codes, negative per-test status, and the combined-run result directory.
- Remote implementation commits: `4aa3fc0983bd717c7c22330a2d95f43f66c35372` (runner), `5d0765da86bc93cae998a2fa41c10adb77f50df2` (regression fixture), and `f6d70d97da1b33de3caa81908989367557ccb3b4` (fixture escape correction).
- The complete `scripts/tests/test-nuvio-phone-benchmark.sh` was not run end-to-end; this environment has no local repository checkout. The focused shell reproduction is not a substitute for that complete regression script.
- Context7 resolution for AndroidX Benchmark 1.5.0 was attempted but the connector returned a quota-exhausted response. No Kotlin, Android API, library or dependency code was changed; the shell-only fix was grounded against AOSP and AndroidX source fetched through Exa.
- No Gradle task, APK build, profile-generation run, device instrumentation, physical-device Macrobenchmark, GitHub Actions workflow dispatch or CI run was started. No app startup speedup is claimed.


## Follow-up audit — startup variability reporting and industry evidence (15 November 2026)

### Improvement cycle 1 — validate AndroidX coefficient of variation

**Confirmed gap**

- AndroidX Benchmark 1.5.0's `BenchmarkData.TestResult.SingleMetricResult` serializes `coefficientOfVariation` alongside `minimum`, `maximum`, `median` and the raw `runs` array.
- The phone runner already checked that all ten samples were positive, that the reported extrema matched the samples, and that the reported median matched AndroidX's interpolated P50. It did not verify the serialized coefficient of variation (CV).
- A malformed or stale CV value could therefore pass the runner while reporting a misleading impression of run stability.

**Change**

- The validator now requires one numeric, non-negative `coefficientOfVariation` for each TTID/TTFD metric.
- It independently recomputes CV from the ten raw samples using AndroidX's formula: sample standard deviation (denominator `n - 1`) divided by arithmetic mean.
- The recomputed and reported CV values are compared with a small floating-point serialization tolerance. Missing, negative, non-numeric, or inconsistent CV values fail closed.
- No arbitrary CV pass/fail threshold was introduced. CV is displayed as a measure of spread, not used to reject a run merely because its device conditions are noisy.

**Source basis**

- AndroidX Benchmark 1.5.0 JSON schema: `BenchmarkData.TestResult.SingleMetricResult` includes `coefficientOfVariation`: https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt
- AndroidX calculation: `MetricResult.kt` computes sample standard deviation with denominator `size - 1`, then `coefficientOfVariation = standardDeviation / mean`: https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/MetricResult.kt

### Improvement cycle 2 — produce a human-readable, properly qualified report

**Confirmed gap**

- After a successful phone run, the output contained the JSON, per-iteration traces, instrumentation log, environment snapshots and a copied-files manifest, but no concise report comparing both startup arms.
- That made users inspect JSON manually and encouraged interpreting a median difference without seeing the sample spread or a warning about the small sample count.

**Change**

- The phone runner now creates `benchmark-summary.txt` after JSON/statistics/trace validation.
- For each of TTID and TTFD, the report shows the no-compilation and Baseline-Profile medians, observed minimum/maximum, CV percentage, and the descriptive median difference in milliseconds and percent.
- The report explicitly says that ten iterations do not establish statistical significance and that a median difference alone is not grounds to claim a speedup. It points the reader to per-iteration traces for skew, thermal throttling and warm-up effects.
- The generated report is included in the output manifest and printed in the LADB session.
- The report does not automatically remove outliers, prescribe an unsupported CV threshold, or claim a statistically significant result.

### What other Android teams measured and learned

These are independent case studies, not predictions of Nuvio's gain:

- **Android Calendar (official Google case study):** controlled releases across a broad device population reported median interactive startup falling from 775 ms to 644 ms (17%), cold startup from 1,058 ms to 901 ms (15%), warm startup from 453 ms to 378 ms (17%), and 42–60% fewer janky frames in the measured views. The team chose profile journeys based on common schedule/month entry points and user data. https://developer.android.com/topic/performance/baselineprofiles/case-study-android-calendar
- **Todoist / Doist:** the phone app's median startup improved by 22.95% (328 ms); the Wear OS app improved by 13.94% (about 268 ms). The engineer used Macrobenchmark and cross-checked the results statistically. The measured effect differed by form factor. https://www.doist.dev/android-app-baseline-profiles/
- **Duolingo:** reported approximately 30% production startup improvement and 25–40% in Macrobenchmark results. Their investigation found invalid profiles after DEX files were modified during packaging, plus a third-party library compressing profile files. Their side-loaded-profile experiment looked promising but did not reflect the final shipped artifact. This supports validating the packaged profile rather than trusting the generated text file alone. https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles/
- **ONCOassist:** an engineer reported ten physical-device cold starts on a Pixel 7 Pro (Android 16): TTID median 500.8 ms without a Baseline Profile versus 427.1 ms with one (14.7%). This is a particularly close comparison-method example, but still a single app/device result, not a target for Nuvio. https://shaheer.blog/how-we-measured-our-android-cold-start-and-got-15-back-from-a-text-file/
- **Statistical interpretation (Block/Square engineering):** Jetpack Macrobenchmark is nondeterministic; thermal throttling, CPU-frequency changes and warm-up can create separate modes or long tails. The author recommends inspecting iteration order and Perfetto traces, not blindly dropping outliers. One proposed 95% confidence-interval method requires normality checks, more than 30 iterations per arm and a variance ratio no greater than two. That is a particular method with assumptions, not a universal Android threshold. https://blog.p-y.wtf/statistically-rigorous-android-macrobenchmarks

### Startup Profile / DEX-layout audit — no redundant setting added

- The live Nuvio generator calls `BaselineProfileRule.collect(..., includeInStartupProfile = true, strictStability = true, filterPredicate = rule -> true, ...)`. Android's API reference confirms the first boolean is `includeInStartupProfile`; the second requests strict profile stability. The journey is the launcher start through fully ready Home, which is the intended startup path.
- The project pins Android Gradle Plugin 9.4.0. Android's Startup Profile documentation says DEX layout optimization has been enabled by default since AGP 8.3; explicitly adding the older opt-in flag would be redundant here. R8/minification is part of the optimized benchmark/release variant setup already recorded above.
- Android documents Startup Profiles as a build-time DEX-layout optimization (not a separate `startup.prof` packaged for ART) and reports that they can add 15–30% startup improvement over Baseline Profiles alone. This is an ecosystem result, not a Nuvio measurement: https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations?hl=en
- The correct verification is the built artifact's DEX layout. For AGP 8.8+, Android documents `r8.json` with a `dexFiles` entry marked `"startup": true` inside the AAB; for APK-only inspection, use APK Analyzer to verify startup-critical classes are placed in the primary `classes.dex`. https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles
- The current phone workflow creates APK pairs and checks for the compiled ART Baseline Profile, but it does not currently produce an AAB or prove primary-DEX Startup Profile placement. No speculative ZIP/metadata assertion was added: the next authorised artifact review should inspect the actual packaged APK (or an AAB if one is built) before deciding how to automate this check.
- The source generator uses `filterPredicate = rule -> true`; no package-only filter was introduced. A broad filter could remove startup-relevant Compose/AndroidX/library methods. Rule composition and primary-DEX fit need the generated profile and build artifact before any change is justified.

### Verification and limits

- Literal POSIX/AWK fixtures accepted all four correct CV values, rejected a numerically plausible but incorrect CV, and rejected a missing CV.
- The summary function was checked with a formatted two-result AndroidX-style JSON fixture. It produced the expected fixture deltas: TTID -70.0 ms (-15.7%) and TTFD -90.0 ms (-12.1%), with the corresponding four CVs. These are synthetic test-fixture values only, not Nuvio device measurements.
- The remote regression fixture now includes valid AndroidX CV fields, `wrong-cv` and `missing-cv` cases, and assertions for the saved report, both timing metrics, median differences and the ten-sample caveat.
- The exact new shell report function passed a local `sh -n` check and the focused output assertions. The full repository `scripts/tests/test-nuvio-phone-benchmark.sh` was not run end-to-end because this runtime cannot resolve GitHub for a local checkout. The focused fixtures are not represented as a full suite pass.
- Context7 resolved the AndroidX library and returned Macrobenchmark source/API snippets; the exact CV fields/formula were not present in its matching documentation response. The pinned 1.5.0 behavior was instead checked directly against AndroidX source and the 1.5.0 API release source through Exa.
- No Gradle task, APK/AAB build, profile generation, Android instrumentation, physical-device Macrobenchmark, workflow dispatch or CI run was started. No Nuvio startup-speed improvement is claimed.


## Benchmark reportability and R8 optimization audit — 15 November 2026

### Completed reporting cycle: preserve the measurement-to-trace relationship

The phone runner's `benchmark-summary.txt` now includes:

- AndroidX device context from `benchmarkData.json`: device model, Android API level, CPU core count, maximum reported CPU frequency, whether CPU clocks were locked, total memory, ART Mainline version, sustained-performance-mode state, and OS codename.
- All ten raw TTID and TTFD samples for each comparison arm.
- The corresponding `PerfettoTrace` filename for each measured iteration.

This is a reporting-only change. It does not change test order, compilation modes, iteration count, app behavior, scheduling, device clock or thermal settings.

**Why the trace mapping is valid:** the AndroidX source used for the existing 1.5.0 JSON/statistics audit shows `MetricResultExtensions.mergeToSingleMetricResults()` assembling each metric's `runs` list by mapping over the ordered iteration-result list. `Macrobenchmark.kt` derives the per-iteration trace paths from that same ordered list and labels them `Trace Iteration <index>`. Thus the Nth raw sample and trace label N correspond. Source references:

- [AndroidX MetricResultExtensions.kt](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/MetricResultExtensions.kt)
- [AndroidX Macrobenchmark.kt](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt)
- [AndroidX Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark)

The regression fixture now checks the device context and each of the 20 iteration/timing/trace rows. A first assertion was confirmed to fail against the previous summary because it had no per-iteration section; focused synthetic AWK fixtures then passed for all 20 rows and the device context. The sample timings remain synthetic test values, not Nuvio measurements. The full repository test script was not run because this environment has no local checkout; no Gradle build, APK/AAB build, device benchmark, profile capture or CI was run.

### Separate speed opportunity: R8 keep-rule impact audit

The live repository configuration was inspected before considering any R8 change:

- `gradle/libs.versions.toml` pins AGP `9.4.0` and AndroidX Benchmark `1.5.0`.
- The app release build type defaults `releaseMinifyEnabled` to true and sets both `isMinifyEnabled` and `isShrinkResources` from that value.
- Release uses `proguard-android-optimize.txt` plus `composeApp/proguard-rules.pro`.
- The repository has one `gradle.properties`; it does not contain `android.enableR8.fullMode=false`.
- R8 full mode has been the default since AGP 8.0, so Nuvio does not need a redundant flag to enable it. AGP 9.0+ also has optimized resource shrinking enabled by default.

The custom rules include package-wide keeps for Supabase, Ktor, QuickJS, plugin and P2P packages, the Nuvio engine, Media3/ExoPlayer, and mpv, plus generated Compose screen classes. Some rules may protect reflection, dynamic loading or native bridges. Their breadth makes them worth measuring, but their presence alone does not prove that they are harming startup or that removing them is safe. **No keep rule was removed or narrowed.**

The best next evidence-gathering step is the **R8 Configuration Analyzer**. Android's current docs say it is integrated from AGP 9.3 and has a standalone Gradle task that emits an HTML report without assembling an APK or App Bundle. Nuvio's AGP 9.4 meets that version requirement. AndroidX's release page currently lists Benchmark 1.5.0 as the latest stable release and 1.6.0-alpha01 as alpha; the repository is already on 1.5.0, and no startup-measurement benefit justifies moving to an alpha. The Android docs' example task is `:app:analyzeReleaseR8Config`; Nuvio has an `:androidApp` module, distribution flavors and generated benchmark build types, so the exact variant task names must be discovered rather than guessed. No analyzer task was run in this session.

Review the report for the most impactful broad, duplicated, subsumed or unused rules, identify their originating file/library and the exact classes/members they retain, then replace only rules proven to be overbroad. Validate reflection, serialization, dynamic loading and JNI paths with appropriate release-like smoke tests before comparing startup traces. Do not set score thresholds or introduce CI automation until a baseline report exists.

Official guidance: [R8 full mode](https://developer.android.com/topic/performance/app-optimization/full-mode), [R8 Configuration Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer), [AGP 9.3 standalone analyzer task](https://developer.android.com/build/releases/agp-9-3-0-release-notes).

### New independent results worth learning from

These are case studies, not forecasts for Nuvio:

- **Grab, 19 October 2026:** reported 12% cold-start improvement from R8 optimization and a further 15% from Baseline/Startup Profiles in its first rollout, then a further 10% startup improvement from R8 full mode. The cumulative reported improvement was 35%. The team used targeted keep rules for reflection and dynamic proxies rather than broad exceptions. [Google Android Developers case study](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time).
- **Tinder, 18 August 2026:** its R8 Configuration Analyzer found an unscoped keep rule from an in-house library. Optimization score rose from 28% to 50% after narrowing the blocker; Tinder reported 47% fewer users experiencing slow cold starts, 17 to 11 DEX files and a 28.98% download-size reduction. This is particularly relevant because it demonstrates that enabling full mode does not guarantee the codebase is actually optimizable. [Google Android Developers case study](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer).
- **Monzo, 30 March 2026:** reported 30% better cold starts, 24% warm starts and 14% hot starts after fully enabling R8 optimizations, with P50/P90 launch improvements of 11%/12%, 9% smaller app size and a 35% lower ANR rate. [Google Android Developers case study](https://developer.android.com/blog/posts/monzo-boosts-performance-metrics-by-up-to-35-with-a-simple-r8-update).

The common actionable lesson is to combine profile-guided startup optimization with a measured audit of what R8 is actually allowed to shrink and optimize. Nuvio already has minification, the optimized default rules file, Baseline Profile and Startup Profile generation configured. The missing evidence is the analyzer output and artifact-level confirmation of startup DEX layout—not another blanket optimization flag.

### Tool and execution limits

Context7's resolver returned a monthly-quota error during this session. Exact AndroidX source, current Android Developers documentation, Exa research and live GitHub files were used instead. No claim is made that Context7 supplied the documentation for this change.

No build, CI, profile generation or physical-device timing run was started, as requested. There is still no new Nuvio TTID/TTFD measurement or verified startup-speed gain from this work.


### Startup Insights and boot-state context — 15 November 2026

Two benchmark-only improvements were added after reviewing AndroidX Benchmark 1.5.0 source and a recent first-hand startup case study.

- **AndroidX Startup Insights enabled for both A/B arms.** StartupBenchmark now passes ExperimentalConfig(null, new StartupInsightsConfig(true)) to the experimental MacrobenchmarkRule.measureRepeated overload. The API was added in AndroidX Benchmark 1.4.0 and is present in the repository's pinned 1.5.0. AndroidX documents that it reports trace-derived startup problem patterns in Android Studio's instrumentation IDE summary; the JSON retains timings and trace metadata, not insight fields. This is diagnostic output; it does not change the app, the compilation modes, or the measured StartupTimingMetric. The shared configuration is used by both CompilationMode.None and CompilationMode.Partial(BaselineProfileMode.Require). The API remains experimental, so it should be revisited when the pinned Benchmark version changes.
- **Device snapshots now record boot context.** The phone runner records ro.build.fingerprint, sys.boot_completed, and /proc/uptime alongside the existing model, API, battery, thermal, saver and brightness fields. This allows results to be stratified by device firmware and time since boot. It is observational only: missing values do not fail a run and no device setting is changed.
- **Test guard coverage** now requires the pinned Benchmark 1.5.0 API/configuration and the new boot fields, including correct single-newline formatting for uptime output. The focused assertions were inspected against the remote source; the full shell regression suite was not run because there is no local checkout in this execution environment.

#### New practitioner evidence

Shubham Singh Bisht's 12 October 2026 first-hand report, *How I Made Our Android App Start 25% Faster*, is unusually useful because it separates lab results from fleet conditions. On a TeachmintX 75 Pro, 10 cold Macrobenchmark iterations showed TTID median changing from 2,758 ms with no compilation to 1,807 ms with the app Baseline Profile on top of library profiles. Before that profile comparison, Perfetto traces attributed about 1,070 ms of a 1,495 ms layout-inflation phase to launch-time WebView/Chromium, LaTeX, icon decoding and ML Kit work that could be deferred. The author also found more than half of field cold starts happened within two minutes of device boot, and debug traces were 4.5× slower than release-like traces. This is one app/device case study, not a prediction for Nuvio; it directly motivates capturing boot age and using Startup Insights to identify Nuvio's own bottlenecks rather than copying their fixes.

Sources:
- [AndroidX Benchmark 1.5.0 MacrobenchmarkRule source](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro-junit4/src/main/java/androidx/benchmark/macro/junit4/MacrobenchmarkRule.kt)
- [AndroidX Benchmark ExperimentalConfig and StartupInsightsConfig source](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-common/src/main/java/androidx/benchmark/ExperimentalBenchmarkConfigApi.kt)
- [Android Developers StartupInsightsConfig API](https://developer.android.com/reference/kotlin/androidx/benchmark/StartupInsightsConfig)
- [Bisht, 12 October 2026: How I Made Our Android App Start 25% Faster](https://shubhamsinghbisht.com/articles/android-app-start-25-percent-faster/)
- [Android Developers: App startup best practices](https://developer.android.com/topic/performance/appstartup/best-practices)

#### Benchmark methodology caution

The two comparison arms remain separate JUnit test methods. JUnit's default method ordering is deterministic but not predictable; do not assume alphabetical order or that the same thermal/device-state sequence applies across all environments. The runner's before/after environment snapshots and per-iteration trace mapping improve diagnosis, but they do not eliminate order effects. For a stronger comparison, future authorized measurements should include repeated independent sessions with the order reversed or balanced, while keeping the same device, build artifacts, data state and cooling/idle conditions. No test-order annotation or execution-order change was made in this update because the present evidence does not establish a single best order for all devices.


### Follow-up source verification — Startup Insights output and R8 task scope

A deeper check against AndroidX Benchmark 1.5.0 source refined one detail in the prior Startup Insights description:

- Startup Insights are calculated per measurement iteration after the trace has been captured and the timing metrics extracted. This keeps the analysis outside the measured app-start interval.
- AndroidX aggregates the findings and passes them to `InstrumentationResults.reportSummaryToIde()`. With Startup Insights enabled, it requests the tree-format IDE summary, where the `App Startup Insights` section is emitted. Nuvio's phone runner already saves the complete `instrumentation.log`, so this channel is retained.
- **Startup Insights are not fields in `benchmarkData.json` in AndroidX 1.5.0.** The `BenchmarkData.TestResult` schema contains metrics, sampled metrics, iteration counts and profiler outputs, but no insights property. The JSON remains useful for timings and trace filenames; the instrumentation log is the place to inspect insight findings. Do not build a JSON parser for a field that does not exist.

R8 Analyzer follow-up from the current pinned AGP 9.4.0 and app variant configuration:

- AGP 9.3+ documents the standalone `analyzeReleaseR8Config` task, which generates the R8 Configuration Analyzer report without completing APK/AAB compilation.
- Android variant naming combines flavor then build type. Nuvio's production release variants are `fullRelease` and `playstoreRelease`; their corresponding task names should be confirmed from the actual Gradle task listing before execution, rather than guessed or added as hard-coded automation.
- The Analyzer should target those minified production release variants first, because `benchmarkRelease` and `nonMinifiedRelease` have different optimization purposes. Do not run `assemble`, `bundle`, profile generation or CI as part of this audit.

Verified source references:
- [AndroidX Benchmark Macrobenchmark.kt](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt) — trace analysis, IDE summary and JSON result construction.
- [AndroidX Benchmark InstrumentationResults.kt](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/InstrumentationResults.kt) — Startup Insights appear in the tree-format IDE summary.
- [AndroidX Benchmark BenchmarkData.kt](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt) — JSON TestResult schema has no insights field.
- [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) — standalone Analyzer task.
- [Android build variants](https://developer.android.com/build/build-variants) — flavor/build-type naming.

Context7 was working for this follow-up. It resolved AndroidX and AGP documentation and returned the 1.5.0 API signature for `ExperimentalConfig`, the `StartupInsightsConfig` constructor, the `MacrobenchmarkRule.measureRepeated` overload, and the AGP 9.3+ standalone R8 Analyzer behavior. The exact AndroidX 1.5.0-rc01 API file confirms Java has the two-argument `ExperimentalConfig(PerfettoConfig?, StartupInsightsConfig?)` constructor used by Nuvio. No Gradle task was executed.

### Correct the raw Startup Profile collector contract — 15 November 2026

A version-pinned source review corrected the first implementation assumption and exposed the exact artifact contract:

- Nuvio pins both AndroidX Benchmark and the Baseline Profile Gradle plugin to 1.5.0. The AndroidX source file at commit 7ac433e44e797de53af85226797862687f37735f (2 September 2026, immediately before the 1.5.0 stable release on 9 September) shows the exact 1.5.0 implementation.
- In BaselineProfileRule.collect(), includeInStartupProfile=true makes the raw collector write only the Startup Profile artifact. With Nuvio's outputFilePrefix="startup", the instrumentation output is startup-startup-prof.txt (plus its timestamped copy). BaselineProfileResult.startupProfiles contains that path; baselineProfiles is empty. It does not emit startup-baseline-prof.txt as a second raw file.
- The Gradle consumer plugin deliberately merges raw filenames containing either baseline-prof or startup-prof into the canonical baseline-prof.txt. It then separately reads startup-prof inputs and writes canonical startup-prof.txt. Thus the generated source-set directory is expected to contain both canonical files even though the phone-side raw instrumentation output for this single Startup Profile collector contains only startup-startup-prof.txt.
- The previous phone runner incorrectly required a raw baseline-prof artifact. That could reject a successful, correctly generated Startup Profile. The corrected phone runner now validates only that at least one fresh, non-empty and non-whitespace *startup-prof*.txt was collected. Its copy step remains scoped to the instrumentation package and fresh output marker.
- The desktop Gradle workflow still correctly requires both canonical baseline-prof.txt and startup-prof.txt after the merge task for each flavor. The regression fixture now models the real raw filename, does not manufacture a nonexistent raw baseline file, rejects missing/empty/whitespace Startup Profile output for both flavors, checks the valid startup-only output, and locks the generator's "startup" prefix plus includeInStartupProfile/strictStability flags.

Pinned source:
- [AndroidX Benchmark 1.5.0 BaselineProfiles.kt at 7ac433e](https://github.com/androidx/androidx/blob/7ac433e44e797de53af85226797862687f37735f/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/BaselineProfiles.kt) — reportResults chooses startup-prof only when includeInStartupProfile=true and returns it in startupProfiles.
- [AndroidX 1.5.0 MergeBaselineProfileTask.kt at 7ac433e](https://github.com/androidx/androidx/blob/7ac433e44e797de53af85226797862687f/benchmark/baseline-profile-gradle-plugin/src/main/kotlin/androidx/baselineprofile/gradle/consumer/task/MergeBaselineProfileTask.kt) — raw baseline/startup inputs are merged into baseline-prof.txt; startup inputs are separately emitted as startup-prof.txt.
- [AndroidX Benchmark 1.5.0 BaselineProfileRule API](https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule) — collect parameter meanings and output prefix.
- [AndroidX Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) — stable release date, 9 September 2026.
- [Android Developers: Debug Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles) — how to prove Startup Profile application to DEX/r8.json.

Verification boundaries:

- The focused shell validator fixture passed: it rejected a missing Startup Profile, an empty file and a whitespace-only file, and accepted startup-startup-prof.txt without any raw baseline-prof file.
- The new generator-argument and raw-output assertions were inspected against the live source. The full repository regression script was not run because this environment has no local checkout. No Gradle task, build, profile generation, APK/AAB, physical-device benchmark or CI was run.
- This proves only the human-readable generator output contract. It does not prove the minified release artifact consumed the profile or placed startup-critical code in primary classes.dex. The next artifact-level check is an AAB r8.json entry with startup=true (AGP 8.8+) or direct inspection of primary classes.dex. No artifact was available or generated.
- No startup-speed improvement is claimed.

### Newer independent performance evidence — 15 November 2026

The newest directly relevant, method-described measured case study found in this review was Sentry's 20 October 2026 engineering report:

- **Sentry Android SDK, 20 October 2026:** Sentry reports 20–30% lower SentryAndroid.init time between SDK 8.43.0 and 8.54.0; a plotted comparison is 43.5 ms to 33.1 ms. It used a synthetic demo app on a Pixel 3 with locked clocks and four interleaved rounds of ten Macrobenchmark runs per SDK version (40 runs per version). Sentry explicitly says this is SDK initialization, not total application startup. Perfetto and more than 40 focused changes informed the work, including reading manifest options from generated bytecode, simplifying reflective class checks, a faster DSN parser, and reusing shared executor pools rather than creating one-off threads. [Sentry engineering report](https://blog.sentry.io/android-sdk-faster-startup/).
- Nuvio's version catalog already pins Sentry Android 8.64.0 and the Sentry Gradle plugin 6.27.0. Both are above the versions Sentry lists as containing these improvements (8.54.0 and 6.20.0), so this report does not justify a dependency bump. Whether Sentry initialization matters to Nuvio must come from Nuvio's own Perfetto trace.
- **Grab, 19 October 2026:** Google reports a 35% cumulative cold-start improvement after several staged changes: an initial R8/profile phase, then R8 Full Mode and keep-rule refinement. The first profile/R8 phase delivered about 27%; the later Full Mode phase added another reported 10%. These percentages are staged case-study outcomes, not an expected Nuvio gain. [Android Developers case study](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time).
- **Tinder, 18 August 2026:** the R8 Analyzer identified an unscoped keep rule from an internal library. Tinder reports optimization score 28% to 50%, DEX count 17 to 11, download size down 28.98%, and 47% fewer users experiencing slow cold starts. The 47% figure is a slow-start user/cohort outcome, not a 47% reduction in median startup milliseconds. [Android Developers case study](https://android-developers.googleblog.com/2026/08/tinder-app-cold-start-r8-configuration-analyzer.html).
- **TeachmintX, 12 October 2026:** a practitioner report on a physical Android 16 classroom panel measured ten cold starts per configuration: median TTID 2,758 ms with no compilation, 2,253 ms with library profiles and 1,807 ms with the app Baseline Profile. Its managed system-app context differs from Nuvio; its useful lesson is trace-first analysis on the target hardware and release-like builds, not copying its fixes. [Engineering write-up](https://shubhamsinghbisht.com/articles/android-app-start-25-percent-faster/).

Benchmark methodology implication: Sentry's four interleaved 10-run rounds provide a useful way to limit time/order drift compared with two separately executed ten-iteration test methods. Nuvio's runner already preserves per-iteration traces and device-condition snapshots, but separate JUnit methods do not eliminate method-order or thermal drift. Retain TTID and TTFD separately and keep the current ten-sample summaries descriptive. A stronger future authorized comparison should use multiple independent sessions with balanced/reversed arm order on the same physical device, keeping app data, APKs, battery, cooling and idle conditions equivalent. Do not add test-order annotations or change the benchmark until real run evidence demonstrates a specific problem.


### ART verification-state fairness across Android API levels — 15 November 2026

A further review found an important limitation in the current A/B design that depends on the Android API level:

- Nuvio permits API 33 and later in `scripts/nuvio-phone-benchmark.sh`.
- In the repository-pinned AndroidX Benchmark 1.5.0 implementation of `CompilationMode.None`, API 34+ runs `cmd package compile verify` and clears the app's ART profiles before measurement. This puts the no-AOT control in a verified-but-not-profile-compiled state, which is materially closer to a Play-installed app than a raw sideload.
- On pre-34 user builds, the same implementation falls back to uninstall/reinstall to reset compilation state. It does not explicitly issue the `verify` compilation command in that path. Nuvio's phone runner installs APKs directly with `pm install`, so API 33 results can include ART verification work in the no-compilation arm that a Play install normally completes at install time.
- This is the same class of confounder described in Expensify's September 2026 investigation: an apparent ~800 ms profile benefit was traced to comparing a raw sideloaded install with an arm whose profile compilation also caused ART verification. Once the baseline was properly verified, the measured difference disappeared for that app. This does not prove Nuvio has the same magnitude or even the same bottleneck, but it makes API 33 results unsuitable for an unqualified Play-user profile-benefit claim.
- **Reporting rule:** retain API 33 support, but label API 33 results separately as sideload/worst-case measurements. Use API 34+ as the primary comparison for estimating the incremental Baseline Profile effect under a Play-like verification state. Never pool API 33 and API 34+ samples into one headline median. Keep device API in every report.
- The user device context currently associated with this project is Android 17 / API 37, so it is in the API 34+ branch. That does not substitute for a fresh benchmark run or prove that the app's profile is correctly packaged/applied.

No runner minimum-API change was made: raising it would unnecessarily remove API 33 coverage, and there is no local checkout available to run the full shell regression suite. No timing, Gradle task, build, APK/AAB, profile capture, device benchmark or CI was run.

Sources:
- [AndroidX Benchmark 1.5.0 CompilationMode.kt at the pinned source commit](https://android.googlesource.com/platform/frameworks/support/+/b7a21ae178189e339dd750865f86fae3219f0101/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt) — API 34+ verify reset versus pre-U uninstall/reinstall fallback.
- [Expensify: Ship an ART Baseline Profile](https://github.com/Expensify/App/issues/99839) — September 2026 correction of a false ~800 ms profile win caused by unequal ART verification states.
- [Android Developers: Benchmark Baseline Profiles with Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile) — intended meaning of None and Partial compilation modes, and recommendation to compare TTID and TTFD.



### Benchmark report comparability labels — 15 November 2026

A concrete reporting gap was found: the runner records the Android API level and installs the benchmark APK with `pm install`, but the human-readable report did not tell readers that the run was sideloaded or explain the API 33 ART reset caveat. This is particularly easy to misread for the `playstore` flavor, whose name is a build flavor, not proof of Google Play delivery.

- The report now explicitly labels the install path as an APK sideload via `pm install`, not Google Play delivery, and says that the `playstore` flavor label is not an install channel.
- API 33 reports now identify the pre-34 `CompilationMode.None` reset path and warn that, on unrooted user builds, uninstall/reinstall can leave the control arm with a different ART verification state from a Play-installed app.
- API 34+ reports identify the AndroidX reset path (`verify` plus `pm art clear-app-profiles`) and still make clear that the APK was sideloaded.
- Unknown API metadata is reported as unknown rather than silently implying comparable state.
- Regression fixtures were extended to assert the API 34+ and sideload labels and to run an API 33 fixture that checks the pre-34 warning.

This change improves interpretation only; it does not change compilation mode, benchmark order, measured code, app behavior or timing. It does not repair API 33 comparability; it makes the limitation visible. The API 33 limitation is grounded in the repository-pinned AndroidX Benchmark 1.5.0 source and in Expensify's September 2026 investigation, where an apparent ~800 ms Baseline Profile gain disappeared after the no-profile arm was put into the same verified ART state.

Additional current research reviewed:
- [Android Developers — Benchmark Baseline Profiles with Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile), last updated 28 October 2026: compare `CompilationMode.None` with `Partial(Require)`, use a physical device, and report both TTID and TTFD.
- [Expensify — Ship an ART Baseline Profile](https://github.com/Expensify/App/issues/99839), updated 16 September 2026: a measured 22% result on a Galaxy A37 (n=10 per arm) was later identified as a verification-state artifact; the corrected profile delta was within noise.
- [Tinder — R8 Configuration Analyzer](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer), 18 August 2026: found a broad keep rule limiting optimization; the 47% figure refers to the slow-start user cohort, not a universal median startup reduction.
- [Grab — R8 and profile-guided optimization](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time), 19 October 2026: reported cumulative 35% startup improvement across R8, Baseline/Startup Profiles and full-mode phases; these were multiple changes in a large super-app and are not a Nuvio estimate.
- [Android Developers — Startup Profiles and DEX layout](https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations): Startup Profiles improve DEX layout; AGP enables DEX layout optimization by default since 8.3. Nuvio is already on AGP 9.4.0, so adding a redundant flag is not a valid optimization. The remaining concrete artifact check is whether startup-critical code fits in primary `classes.dex`.

No Gradle task, build, APK/AAB, profile capture, physical-device benchmark, Actions dispatch or CI was run. The updated regression test was not executed in this environment. GitHub read-back confirmed the runner, fixture and both notes contain the committed changes; the branch comparison shows no commits behind the starting checkpoint. No Nuvio startup-speed gain is claimed.


## Deeper Startup Profile and R8 configuration audit — 15 November 2026

### Confirmed source facts

- The generator collects 15 maximum iterations / 3 stable iterations, includes the launcher-to-fully-drawn path in the Startup Profile, and uses an unfiltered rule predicate. The checked-in app Baseline Profile text file is 1,409,345 bytes according to the Git tree metadata.
- Android's API reference says `filterPredicate` filters individual rules, defaults to no filtering and only works when target app code is not obfuscated. Its package-restricted example is specifically for a library-only profile. Since Nuvio's profile capture is against its non-minified variant, there is no evidence that the current unfiltered predicate is a bug. Filtering to `com.nuvio` could remove dependency code needed during startup.
- The compiled `baseline.prof` must remain below 1.5 MB; the human-readable 1,409,345-byte text file is not that binary and must not be judged against the binary limit.
- Startup Profile inclusion is correctly restricted to the initial launcher-to-ready journey. The unresolved artifact question is whether R8 actually placed the startup partition in primary `classes.dex`. Android supports verification through APK Analyzer or, for AGP 8.8+, the AAB `BUNDLE-METADATA/com.android.tools/r8.json` metadata with a `startup: true` DEX entry.
- Nuvio pins AGP 9.4.0, which meets the AGP 9.3+ requirement for the standalone R8 Configuration Analyzer task. The official task skips APK/AAB packaging and produces an HTML report; it is a more direct next diagnostic than manually guessing at keep-rule impact.
- Static keep-rule inspection identified broad package-wide app rules in `composeApp/proguard-rules.pro` for Supabase, Ktor, Media3, legacy ExoPlayer, MPV, QuickJS, plugin, P2P and engine packages. These are candidates for analyzer quantification only. Some may be needed for reflection, JNI, serializers or dynamic plugin loading, so no rule was edited.

### Concrete next sequence

1. Run the standalone R8 Configuration Analyzer for both minified distribution release variants and inspect the largest in-app rules by blocked optimization/shrinking impact. This task does not package an APK/AAB, but it is a Gradle task and was not run in this no-build/no-CI session.
2. Only refine a rule if the report shows a material in-app impact and the corresponding reflection/JNI/serialization contract can be preserved with narrower members.
3. In the next authorized artifact build, inspect AAB `r8.json` for a DEX marked `startup: true`, and cross-check the DEX checksum against the actual packaged DEX. Alternatively inspect the APK's DEX layout with Android Studio APK Analyzer.
4. Then use the same physical device and account to compare before/after TTID and TTFD across repeated independent sessions, retaining per-iteration traces and thermal snapshots. Do not accept a median-only win.

### Practitioner evidence

- Monzo (30 March 2026) reported 30% cold-start, 24% warm-start and 14% hot-start improvements after enabling R8 optimizations, with P50/P90 launch improvements of 11%/12%.
- Tinder (18 August 2026) reported a 47% reduction in slow cold starts after using R8 Configuration Analyzer to find and refine an overly broad in-house keep rule. The result is for the slow-start cohort, not a universal median.
- Grab (19 October 2026) reported a cumulative 35% startup improvement across R8, Baseline/Startup Profiles and keep-rule refinement; multiple interventions in a large super-app mean this is not a Nuvio forecast.
- P.Y. Ricau's benchmark analysis shows why iteration order and per-iteration traces matter: thermal throttling can create multimodal results, and warm-up/cache effects can create long tails. Nuvio's existing 10-iteration report is suitable for screening, not a statistically strong final claim.

No application source, keep rule, profile generator, iteration count or test order was changed. No standalone analyzer Gradle task, build, APK/AAB generation, profile collection, device benchmark, workflow dispatch or CI was run. No Nuvio performance gain is claimed.


## Research and source-level cross-check — 15 November 2026, 22:28 UTC

### New practitioner findings and what they mean for Nuvio

- **Reddit / Android Developers (17 November 2025):** Reddit reported a 40% faster cold start after enabling R8's full optimization potential, with 30% fewer ANRs, 25% better frame rendering and 14% smaller app size in production. A separate controlled Macrobenchmark comparison combining R8, Baseline Profiles and Startup Profiles reported 55% faster startup and 18% earlier browsing. These are two distinct result sets; the 55% result is not attributable to R8 alone. Nuvio already enables minification/resource shrinking with `proguard-android-optimize.txt`, so the transferable next step is to quantify its broad keep rules with R8 Configuration Analyzer, not repeat the basic R8 switch. [Reddit case study](https://developer.android.com/blog/posts/how-reddit-used-the-r8-optimizer-for-high-impact-performance-improvements).
- **Gojek Project Butter (12 January 2026):** the team used Perfetto to find DEX loading/class verification work, and reported a large app-specific launch reduction after several changes. It calls out eager initialization, dependency-graph work and dynamic `ServiceLoader` discovery as possible costs. Importantly, Gojek also says its experiment with Android App Startup saved only a few milliseconds and was dropped. This is a useful diagnostic pattern, not a reason to add App Startup or replace dynamic loading in Nuvio without proving that those mechanisms are on Nuvio's launch path. [Gojek engineering report](https://www.gojek.io/blog/project-butter-a-journey-for-app-performance-improvement).
- **Android's current app-startup guidance:** recommends deferring nonessential library initialization until after the first frame, using on-demand initialization where supported, keeping initial composition light, and using cold-start Perfetto traces to find the real work. It recommends the App Startup library specifically to consolidate multiple component providers; that should be considered only after inspecting the merged manifest, not inferred from dependencies alone. [Android app optimization best practices](https://developer.android.com/topic/performance/appstartup/best-practices).
- **Sentry (20 October 2026):** its 20–30% improvement measures `SentryAndroid.init` itself on a synthetic Pixel 3 app, not total app startup. Nuvio already pins Sentry Android 8.64.0 and plugin 6.27.0, both beyond the stated minimum versions, and explicitly enables the plugin's runtime optimizations. No further Sentry version bump or deferral is justified by that report. [Sentry engineering report](https://blog.sentry.io/android-sdk-faster-startup/).

### Nuvio source crosswalk (read-only audit at branch HEAD `8eae9b6290df3041cf998a59f21ff5ea6311d835`)

- `MainActivity.onCreate` currently invokes **78 explicit `.initialize(...)` calls before `showContent()` and before the first Compose composition**. This is a concrete audit surface, not proof that all 78 calls are expensive. Source inspection of representative initializers found several are just context/reference wiring: `P2pStreamingEngine.initialize` stores the application context; `AppFileCache.initialize` stores a cache-directory reference; `EpisodeReleaseNotificationPlatform.initialize` stores application context; `AndroidAppUpdaterPlatform.initialize` stores application context. `ProfileBiometricAuth.initialize` stores a weak activity reference and schedules legacy-key cleanup on IO rather than performing Keystore cleanup inline.
- `NuvioApplication.onCreate` calls `DnsPlatform.initialize`; the inspected implementation reads one SharedPreferences value and selects the DNS provider. There is no source evidence that this operation is a material startup bottleneck.
- The existing `StartupPreloader` already separates a critical phase from post-frame warmup. The critical phase awaits profile cache, watched data, watch progress, continue-watching preferences and the Home snapshot; download-platform preparation is launched concurrently. Library, Live TV, player/P2P settings, server, metadata settings and download-transfer-store warmup are in the post-frame phase. Moving these calls wholesale would risk changing readiness/offline behavior and is not supported by current evidence.
- The checked-in app manifests explicitly declare a FileProvider; dependency-merged providers are not visible in source manifests. Without the merged manifest from an authorized build, there is no basis to add another startup provider or migrate initializers to AndroidX App Startup.
- The existing Startup Profile already targets launcher-to-fully-drawn startup. The unresolved high-value artifact check remains whether R8 places the startup partition in primary `classes.dex`; a human-readable profile file or a successful collector run does not prove this.

### Benchmark evidence boundary

The only retrievable attachment for the current phone attempt is `current-install.txt`, whose complete content is `install-profile:playstore`. This identifies the runner action only. It is not evidence that profile collection completed, that a benchmark ran, or that either profile arm produced measurements. No `instrumentation.log`, `benchmarkData.json`, Perfetto trace, or before/after environment snapshot was available in the supplied material. Therefore no Nuvio timing conclusion or runtime change is supported yet.

### Concrete next actions (no source guesswork)

1. Run the standalone R8 Configuration Analyzer for both minified distribution variants when a local execution environment and explicit Gradle-task authorization are available. Rank the actual rules by blocked optimization/shrinking impact; retain rules required for reflection, JNI, serialization and dynamic plugin behavior.
2. In the next authorized artifact build, inspect the merged manifest and AAB `BUNDLE-METADATA/com.android.tools/r8.json` (or APK Analyzer) to verify provider initialization and primary-DEX Startup Profile placement.
3. For runtime work, inspect the existing Startup Insights/Perfetto traces around the 78 MainActivity initializers and the critical preloader steps. Defer only operations shown to be nonessential and materially expensive; keep required state and download/offline readiness intact.
4. For profile A/B comparisons, follow Sentry's useful drift-control pattern of multiple interleaved rounds, or at minimum run separate sessions in balanced/reversed arm order on the same device. Keep TTID and TTFD separate and retain every iteration and trace. Do not pool API 33 with API 34+.

Context7 was queried in this pass, but its retrieved Macrobenchmark API snippet referenced an older 1.1.0-beta02 source snapshot rather than the pinned 1.5.0 implementation. No version-sensitive code change was made from that stale snippet; the exact 1.5.0 AndroidX source references already recorded above remain the authority.

No application source, benchmark source, keep rule, dependency, profile or runner behavior was changed. No Gradle task, build, APK/AAB generation, profile capture, device benchmark, workflow dispatch or CI was run. No Nuvio startup-speed improvement is claimed.


## Benchmark fidelity and ART diagnostics audit — 15 November 2026, 22:36 UTC

### Finding 1 — benchmarkRelease optimisation inheritance: previous diagnosis withdrawn

The earlier claim that the custom `benchmarkRelease` block necessarily disables or omits production R8 optimisation was incorrect. The Baseline Profile Gradle plugin is pinned to **1.5.0** (`androidx-benchmark = "1.5.0"`) and creates/configures the `benchmarkRelease` and `nonMinifiedRelease` build types itself. Android's official [Baseline Profile configuration guide](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles) explicitly says these generated variants are automatically configured for their intended use and generally need no further customization. It demonstrates a user-defined `create("benchmarkRelease")` block that changes only the signing config—the same customization pattern used by Nuvio, which additionally sets the isolated `.benchmark` application ID suffix.

Therefore, the absence of an explicit `initWith(getByName("release"))` in Nuvio's customization block is **not evidence** that minification, resource shrinking, optimized ProGuard rules, non-debuggable/profileable settings or other plugin-managed benchmark properties are missing. The previous recommendation to add `initWith` is withdrawn; do not make that change.

The current source inspection establishes that Nuvio customizes the generated benchmark variant's signing and application ID. To prove exact resolved variant properties for AGP 9.4.0 + Baseline Profile plugin 1.5.0, inspect the generated variant model or the actual benchmark APK manifest/R8 outputs during an explicitly authorized diagnostic build. Until then, treat the plugin-managed benchmark configuration as the documented intended behavior, not a defect.

### Finding 2 — API 33 unrooted Macrobenchmark reset clears the authenticated test state

AndroidX's current `CompilationMode.kt` implementation uses `reinstallPackage()` on pre-API-34 unrooted user builds. That method explicitly copies the APK, calls `pm uninstall`, then reinstalls it; its source comment states this path does not preserve app data. Rooted pre-34 sessions instead use `cmd package compile --reset`. Android's current Macrobenchmark guide now explicitly says to use API 34+ to persist state when benchmarking.

Nuvio's phone runner accepts API 33 and only prints a post-run ART caveat. The current Nuvio benchmark requires an authenticated Home screen: `StartupBenchmark.awaitMainContentReady()` waits for `nuvio_benchmark_home` and `nuvio_startup_ready`, while the install instructions require signing in first. On API 33 unrooted, both `CompilationMode.None` and `Partial(Require)` can uninstall/reinstall the app before measurement and erase that sign-in/profile state. The expected symptom is a Home readiness timeout or an unintended auth/onboarding path, not a valid Home startup sample.

**Recommended correction:** preflight API 33 root capability before installing/running this authenticated benchmark. Allow API 33 only when the Macrobenchmark shell can actually run root commands (AndroidX checks both a root shell session and usable `su root id`); otherwise stop with a clear API 34+ requirement. Keep API 34+ behavior unchanged. Do not simply suppress compilation resets: that would invalidate the comparison unless compilation is controlled independently. The current report's API 33 warning is insufficient because it omits app-data loss.

References: [Macrobenchmark overview — state persistence on API 34+](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [AndroidX CompilationMode implementation](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt).

### Finding 3 — add ART metrics to validate profile effectiveness, not just launch time

The current `StartupBenchmark` collects only `StartupTimingMetric` plus Startup Insights. AndroidX Benchmark 1.5.0 includes the public `ArtMetric`, which can add diagnostic evidence for:

- `artJitCount` and `artJitSumMs`: JIT compilations and total JIT time during the measured startup.
- `artVerifyClassCount` and `artVerifyClassSumMs`: runtime class verification count and duration.
- `artClassLoadCount` and `artClassLoadSumMs`: runtime class loading count and duration, when the device supports the tracepoints.

The class-loading tracepoints require API 35+, or API 31+ with ART Mainline version at least `341511000`. Nuvio's runner already records API and ART Mainline version, so the report can state whether class-load metrics should be expected. AndroidX 1.5.0 release notes include the R8/AGP 9.1+ fix for ArtMetric class-load detection, relevant to this repository's AGP 9.4.0. AndroidX filters ArtMetric slices by the target package name, so these counts are limited to the measured app package (including libraries packaged into that app), not arbitrary Android framework processes. They remain diagnostic comparisons rather than a direct count of Nuvio-authored methods.

**Recommended benchmark-only enhancement:** include `ArtMetric()` alongside `StartupTimingMetric()` in both comparison arms and surface its optional JSON measurements in the summary. Keep TTID/TTFD as the user-facing primary metrics. Do not make class-load metrics mandatory on devices that lack the tracepoints. This does not alter application runtime behavior, but it still needs a regression test for JSON parsing and a version-checked benchmark build before use.

References: [ArtMetric API](https://developer.android.com/reference/androidx/benchmark/macro/ArtMetric), [AndroidX Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark).

### Finding 4 — one 10-sample batch per arm is not enough to rule out session drift

Nuvio currently runs two separate JUnit test methods, each with ten cold-start iterations. AndroidX resets/compiles once before each `measureRepeated` benchmark and then performs the ten measured iterations. The two arms therefore run as separate batches rather than interleaved samples. The phone runner captures device conditions before and after the whole instrumentation invocation, not between arms.

Sentry's October 2026 SDK performance report describes four interleaved rounds with ten runs per version (40 runs per version) on a Pixel 3 with locked clocks. This is a useful stronger pattern for detecting thermal/order drift; it is not a universal requirement or a guaranteed sample size for Nuvio. Nuvio should use repeated paired sessions with arm order reversed/balanced, preserve all raw iterations and traces, and compare distributions and per-arm environment snapshots. Do not increase iterations blindly or treat ten-run medians as significance.

Reference: [Sentry Android SDK performance methodology](https://blog.sentry.io/android-sdk-faster-startup/).

### Implementation boundary

These findings are source- and documentation-backed. No build-type, phone-runner, benchmark-source or parser code was changed in this pass: a local checkout/execution environment is unavailable, so the required failing regression tests cannot be run before source changes. No Gradle task, build, APK/AAB, benchmark, device session or CI was run. The next evidence-led cycle should verify the resolved benchmark APK properties (without adding redundant build-type inheritance), tighten API 33 reporting/preflight only if the instrumentation-shell root state can be checked reliably, then consider optional ArtMetric and balanced paired sessions. Validate with the shell regression suite and an authorized benchmark build before making performance claims.


## Correction — Baseline Profile plugin owns benchmarkRelease configuration — 15 November 2026, 23:10 UTC

The 22:36 UTC finding above that `benchmarkRelease` lacked release R8/resource-shrinking configuration because its user customization block omitted `initWith(release)` was incorrect and is withdrawn. Repository version catalog pins AGP 9.4.0 and AndroidX Baseline Profile Gradle plugin 1.5.0. Android's official plugin configuration guide states the plugin creates and configures `benchmarkRelease` and `nonMinifiedRelease`, and that user blocks customize selected properties. Its Kotlin example specifically customizes the generated `benchmarkRelease` by changing only signingConfig. Nuvio's custom block sets debug signing and the isolated `.benchmark` application ID suffix, matching that supported pattern.

No `initWith` change is warranted. Exact resolved variant properties still require a generated-variant report or inspection of the APK produced by an authorized build. Do not describe the current benchmark as unminified based solely on the source block.

API 33 reset nuance: AndroidX 1.5.0 source calls `Shell.isSessionRooted()`; this returns true if the instrumentation shell is already root **or** if `su root id` succeeds. In that case it runs `cmd package compile --reset`, preserving app data. Only the unrooted pre-API-34 branch calls `reinstallPackage()`, whose source explicitly says it does not preserve app data. A root check from the outer LADB shell is not conclusive proof of the instrumentation shell's result. Safest reproducible path remains API 34+; for API 33, report the exact instrumentation root capability or reject the run rather than assume from API level alone.

New high-signal evidence reviewed:
- [Android Baseline Profile plugin configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles): generated benchmark build types and customization contract.
- [Android Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview): measured target should be profileable, non-debuggable and as close to production as possible; minification is preferred.
- [Android Calendar controlled-release case study](https://developer.android.com/topic/performance/baselineprofiles/case-study-android-calendar): Baseline Profiles produced 15% median cold-start and 17% median interactive-start improvements in production, plus 42–60% fewer janky frames in key views. This supports measuring user-interactive readiness (TTFD) alongside TTID.
- [ONCOassist cold-start measurement](https://shaheer.blog/how-we-measured-our-android-cold-start-and-got-15-back-from-a-text-file/): 14.7% TTID improvement (500.8 ms to 427.1 ms) across ten cold starts on Pixel 7 Pro, using a minified benchmark APK and a separate non-minified profile-generation variant. This is a useful small-app benchmark example, not a forecast for Nuvio.
- [Tinder R8 Configuration Analyzer case study](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer): 47% reduction specifically in the slow-start cohort, after identifying unintentional keep-rule blockers; not a 47% median improvement for all users.

No source/configuration or runtime code was changed. No Gradle task, build, APK/AAB, profile capture, device benchmark or CI was run.

## Benchmark mechanics, ART metric schema and fresh practitioner evidence — 15 November 2026

### Confirmed positive: cold means cold disk/cache state on every measured iteration

A source-level re-check of AndroidX Macrobenchmark's `StartupMode.COLD` path confirms that, for each cold iteration, it:

1. runs the benchmark setup (Nuvio presses Home),
2. calls `dropShaderCache()`,
3. kills the target app process,
4. calls `dropKernelPageCache()`, and
5. verifies the target package is no longer running before the measured launch.

AndroidX documents shader-cache clearing as part of `StartupMode.COLD`; the operation uses ProfileInstaller 1.3.0-alpha02+ or a rooted-device fallback. Nuvio pins ProfileInstaller **1.4.1**, so it exceeds that minimum. Kernel page-cache clearing is available without root from API 31 via Android's `perf.drop_caches` property; Nuvio's supported phone runner starts at API 33. This is materially stronger than force-stopping the process alone: the measured launch is not simply reading the app from a still-warm kernel page cache.

References: [AndroidX Macrobenchmark cold-start implementation](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt), [MacrobenchmarkScope cache APIs](https://developer.android.com/reference/androidx/benchmark/macro/MacrobenchmarkScope), [Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark).

**Do not add manual cache-clearing commands to Nuvio's runner.** That would duplicate AndroidX's per-iteration behavior and could make the benchmark less comparable to the library's supported setup. The API 33 unrooted app-data loss issue is separate: it occurs during the compilation reset before iterations, not in the per-iteration cold-cache sequence.

### Confirmed positive: tracing capability and TTFD readiness are already wired

- `androidApp/src/main/AndroidManifest.xml` explicitly contains `<profileable android:shell="true" />`. This is the app-owned source manifest; the merged, resolved manifest is still an artifact-level check.
- `MainActivity.kt` uses Compose `ReportDrawnWhen { appReady.value }`. That state is set by AppGate's `onAppReady` callback. The `nuvio_startup_ready` resource marker is exposed from the same ready state, while `nuvio_benchmark_home` identifies the actual Home route.
- `StartupBenchmark.awaitMainContentReady()` waits for Home and the fully-drawn marker. Therefore TTID and TTFD are distinct and the TTFD signal is tied to the usable Home readiness contract rather than merely to the splash screen or first Compose frame.

No replacement readiness signal or extra profiling manifest declaration is justified from the current source review.

### ART metrics: exact 1.5.0 result names and limitations

The previous ART metric note used an imprecise shorthand for the class-verification count. AndroidX 1.5.0's public `ArtMetric` emits these exact measurement names:

| Measurement | Meaning |
|---|---|
| `artJitCount`, `artJitSumMs` | JIT compilation events during the measured app journey and their total duration |
| `artVerifyClassCount`, `artVerifyClassSumMs` | Runtime class-verification events and total duration |
| `artClassLoadCount`, `artClassLoadSumMs` | Runtime class-load events and total duration, only when class-load tracepoints are supported |

The first two metric pairs are available from API 24+. Class-load tracepoints are available on API 35+, or API 31+ when the ART Mainline version is at least `341511000`. The AndroidX 1.5.0-alpha05 change specifically corrected class-load detection with AGP/R8 9.1+, relevant to Nuvio's AGP 9.4.0. AndroidX filters the ART slices by the target package; the values are diagnostics for the app process, not a direct wall-clock breakdown of all system work.

A useful interpretation caveat: AndroidX notes that release APK classes are typically verified at install time, so runtime class-verification counts may correctly be zero. JIT and supported class-load metrics are the more useful signals for checking whether the packaged profile changes runtime work. A lower ART count is not itself proof of a faster user-visible launch; TTID/TTFD and traces remain primary.

Nuvio's current `StartupBenchmark` only requests `StartupTimingMetric`, and the phone runner validates and summarizes TTID/TTFD. ArtMetric is a worthwhile **benchmark-only follow-up**, but it must be integrated end-to-end: optional metric parsing, a schema regression fixture, per-arm reporting, and graceful absence of class-load fields on unsupported ART versions. Adding the metric to the Java list alone would not make its results useful in the phone report. No such change was made without a local test run.

References: [ArtMetric API](https://developer.android.com/reference/androidx/benchmark/macro/ArtMetric), [AndroidX ArtMetric implementation](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Metric.kt), [AndroidX ArtMetric tests](https://github.com/androidx/androidx/blob/66dad59938a20f2975af8594868b6dd28ca0146e/benchmark/benchmark-macro/src/androidTest/java/androidx/benchmark/macro/ArtMetricTest.kt), [Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark).

### Clock-locking is not a missing runner feature

Benchmark 1.5.0 added the optional `androidx.benchmark.requireLockedClocks` check for rooted physical devices. Android's Macrobenchmark guidance distinguishes Macrobenchmark from CPU-bound Microbenchmark and says clock locking is primarily necessary for Microbenchmark. Nuvio's phone runner already records whether clocks are reported locked as environment metadata. Do not attempt to force-lock clocks on an ordinary non-rooted user handset or reject a useful real-device run solely because this flag is false. Instead, retain device/thermal/battery context and repeat sessions when the result is close to the noise floor.

### Recent practitioner evidence and confidence

- **Genvi (2 Oct 2026; updated 3 Nov):** reported cold start from 7.3 s to 3.2 s on its release Android app. Its five-launch median was tied to showing the Home user's identity. The team attributed the improvement to loading large editor/3D/PDF/media modules only on demand, initializing tabs when first opened, and avoiding an initial empty Home render followed by a second render after local account data became available. This is a useful engineering pattern, but its five-run sample and limited device/method detail make it directional rather than a benchmark standard. [Case study](https://genvi.co/blog/cutting-phone-cold-start-from-7-to-3-seconds).
- **TrulyMadly Android engineer (7 Nov 2026):** described removing a redundant launch API call, skipping network refresh when cached data is fresh, deleting obsolete login-screen configuration work, deferring heavy hidden screens, and moving noncritical DataStore/cache work off the main thread. No comparable before/after timing series was provided, so this is qualitative practitioner evidence, not a measured gain. [Write-up](https://www.linkedin.com/posts/pulkitloya_android-kotlin-androiddevelopment-activity-7524833478069399552-UzGB).
- **RKMovies (13 Nov 2026):** its author claims 250–350 ms cold TTID and sub-500 ms interactive Home on a 2 GB Moto G Play after removing launch providers, delaying a video surface, and moving parsing/decode work out of the first-frame path. It is a self-published product post with a single-device result and no raw benchmark distribution, so it is low-confidence and not a transferable target for Nuvio. [Write-up](https://dev.to/hammiwor/why-my-1.17-mb-player-opens-in-under-400-ms-cold-zero-content-providers-lazy-init-and-a-startup-1i1h).

The common lesson is not "remove providers" or "make everything lazy". It is to identify work on the first usable Home path that is not needed to make Home usable, then prove its contribution in Nuvio's traces. Nuvio already has a critical/post-frame preloader split and a Home readiness contract. Do not defer more profile, playback, offline or Home data based only on another app's result.

### Revised next sequence

1. Obtain the standalone R8 Configuration Analyzer reports for the actual minified `fullRelease` and `playstoreRelease` variants once Gradle-task authorization and an execution environment are available. AGP 9.4.0 meets the AGP 9.3+ requirement, and the standalone task skips APK/AAB packaging, but it is still a Gradle task and was not run.
2. Inspect the reports for rules that block optimization of Nuvio-owned startup code; validate reflection/JNI/serialization/plugin contracts before narrowing any rule.
3. Verify the compiled Startup Profile was consumed and startup classes fit in primary `classes.dex` using the AAB `BUNDLE-METADATA/com.android.tools/r8.json` metadata or APK Analyzer.
4. Only then evaluate optional ArtMetric integration and trace-led critical-path changes. Preserve raw iteration traces and compare repeated paired sessions before making small-gain claims.

No runtime/build/benchmark source changed. No Gradle task, build, APK/AAB, profile collection, device benchmark, workflow dispatch or CI was run.

### ArtMetric package-scope clarification — 15 November 2026

The earlier line saying that ART JIT metrics may include uncompiled Android framework code was inaccurate for AndroidX 1.5.0 `ArtMetric`. The implementation queries ART trace slices with `packageName = captureInfo.targetPackageName`, so its measurements are filtered to the target application package. They can include app-packaged third-party libraries and generated code, so they are still not a count of Nuvio-authored methods, but they do not aggregate unrelated Android framework packages. The exact measurement names and support conditions above remain unchanged.

Source: [AndroidX Benchmark Metric.kt](https://github.com/androidx/androidx/blob/66dad59938a20f2975af8594868b6dd28ca0146e/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Metric.kt).

## Field startup variance, ART runtime images and newer benchmark tooling — 15 November 2026, 23:38 UTC

### New field evidence: cold start is not one homogeneous condition

Shubham Singh Bisht's 12 October 2026 first-hand report, *How I Made Our Android App Start 25% Faster*, is unusually useful because it combines a shipped Android whiteboard app, a week of telemetry from thousands of classroom panels, Perfetto traces, production-like builds and Macrobenchmark:

- The deployed fleet's cold-start median was about **4.5 seconds**; on panels with more than four hours uptime it was about **2.5 seconds**.
- More than half of cold launches happened within two minutes of boot. The author's desk measurements, taken after days of uptime, therefore represented a materially easier environment.
- Play Console was reporting a different firmware population (mostly Android 14) from the author's deployed fleet (mostly Android 16). Splitting by API level and device cohort explained the apparent disagreement between the dashboard and local tests.
- A release-like trace on a TeachmintX 75 Pro attributed 1,495 ms to layout inflation, 612 ms to first-frame work, 310 ms to bindApplication and 142 ms to process start. WebView/Chromium startup, LaTeX, icon decoding and ML Kit accounted for roughly 1,070 ms of the inflation cost and were not required to display the first usable board.
- Deferring hidden ComposeView content, changing expensive hidden hosts to on-demand creation and moving bitmap decoding after the first frame each produced measured, isolated changes. The hidden ComposeView change alone improved cold start by 55 ms in that app; the author also documented a logout/login regression caused by callbacks assuming the lazy WebView already existed.
- Moving work to background dispatchers was not automatically beneficial: on the low-core panel it competed with the UI thread and reduced the TTID-to-TTFD gap improvement. The report explicitly treats first-frame and fully-drawn as separate outcomes.

The report's headline comparison was 2.4 s to 1.8 s to first frame on the same panel. Separately, its ten-run Macrobenchmark comparison on the TeachmintX 75 Pro measured 2,758 ms without compilation and 1,807 ms with the app Baseline Profile (about 34.5% faster); that profile added about 450 ms beyond library profiles alone. These are different experiments/devices and must not be combined into one claimed percentage.

**Nuvio-specific implication:** the phone runner already records `/proc/uptime`, boot-completed state, battery and thermal-service snapshots immediately before and after the entire Macrobenchmark invocation. This is good existing instrumentation; do not duplicate it. However, the uptime is not included in the human-readable summary and the snapshots bracket both test methods rather than each arm separately. For field-representative comparisons, retain those raw snapshots and repeat the complete paired run in two clearly labelled conditions: shortly after reboot (e.g. under two minutes) and after the device has settled (e.g. several hours). Do not mix the two populations into one median or treat the post-boot result as a code regression without the matching control.

**Do not blindly copy the reported changes.** Nuvio is a Compose Multiplatform app rather than the report's XML/View-heavy classroom board. Its WebView, ML Kit, LaTeX and 4K view hierarchy are not Nuvio's startup workload. The transferable rule is to prove which work is needed before Home is usable, and to protect TTFD when deferring work or moving it off the main thread.

Source: [How I Made Our Android App Start 25% Faster](https://shubhamsinghbisht.com/articles/android-app-start-25-percent-faster/).

### Independent confirmation: R8 and custom profiles complement Cloud Profiles

Google's 19 October 2026 Grab engineering case study reports a cumulative **about 35% cold-start improvement**, about 32.5% lower user-perceived ANRs and about 30% smaller download size across R8 optimization, Baseline Profiles, Startup Profiles, R8 Full Mode and keep-rule refinement. The team reports an initial 27% cold-start improvement from its R8 + profile-guided optimization phase, then a further 10% from R8 Full Mode. It also explains that Play Cloud Profiles did not cover all app-specific startup paths (including Dagger graph initialization), which motivated custom Baseline Profiles for critical journeys.

The relevant lesson for Nuvio is not to copy Grab's percentage. Nuvio already has R8-enabled benchmark variants, Baseline Profile generation and Startup Profile collection. The next meaningful evidence is the actual R8 Configuration Analyzer output and the built APK's consumed profile/DEX metadata. Grab's case study reinforces that keep-rule hygiene can matter after the basic R8/profile setup is in place.

Source: [Grab: R8 and profile-guided optimization](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time).

### AndroidX 1.5.0 ART runtime-image controls are already part of this benchmark

A further source review of the pinned AndroidX Benchmark **1.5.0** behavior confirmed an important control not captured in the earlier cold-cache summary:

- API 34+ ART can create runtime images that make later launches faster even when a benchmark intends to measure a no-compilation/verify baseline.
- Benchmark 1.5.0's `CompilationMode.None` explicitly requests runtime-image clearing between kills when supported.
- On API 35 (and API 34 with the required ART Mainline backport), AndroidX uses the verify path to clear the image. On supported devices where verify cannot clear it, AndroidX uses its runtime-image poisoning workaround before the measurement phase and waits for the image flush. This is an intentional library safeguard, not a Nuvio runner omission.
- The Baseline Profile arm uses the library's profile installation/reset path; no custom shell command should be added to try to clear ART images manually.

This confirms that Nuvio should continue to rely on the pinned Macrobenchmark library rather than layering independent ART resets on top. The runner already records the ART Mainline version so a result can be interpreted against device capability. The API 33 data-loss distinction remains separate: pre-34 unrooted user builds use uninstall/reinstall for compilation reset, while rooted instrumentation shells use the reset command and preserve app data.

Sources: [Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark), [CompilationMode source](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt), [MacrobenchmarkScope source](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/MacrobenchmarkScope.kt), [DeviceInfo runtime-image capability logic](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/DeviceInfo.kt).

### Latest AndroidX release status: do not jump to alpha for this startup comparison

As of 15 November 2026, AndroidX Benchmark **1.5.0 is the latest stable release**; 1.6.0-alpha01 was published on 7 October. The alpha adds:

- `MemoryProfilingConfig` to capture Perfetto heap profiles in an additional profiling pass, separate from regular timing iterations. This could be useful later if Nuvio's Home-ready traces point to allocation/heap pressure, without contaminating the TTID/TTFD timing pass.
- A `:lockClocks` task that uses fixed performance mode where available on more physical devices. The published release notes describe this as a Benchmark Gradle plugin capability primarily useful for stable Microbenchmarks; it is not a reason to change Nuvio's current Macrobenchmark or force clock locking on the user's non-rooted phone.

Keep 1.5.0 as the benchmark baseline. A 1.6 alpha experiment should be isolated and only considered for a specific memory-diagnostic question, not adopted as an unmeasured startup-speed fix.

Source: [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark).

### Revised concrete next steps

1. Keep the existing uptime/battery/thermal snapshots; use them to label a settled-device run and a post-boot run rather than adding another environment-capture mechanism.
2. Preserve TTID and Home-ready TTFD as joint acceptance criteria. Any deferred initialization must be evaluated for CPU contention and TTFD regression on lower-core devices.
3. Obtain the R8 Configuration Analyzer reports for minified `fullRelease` and `playstoreRelease` when a Gradle task is authorized; inspect actual keep-rule blockers before editing any rule.
4. Inspect compiled Startup Profile and primary DEX metadata in the built artifacts.
5. Only add ArtMetric or a 1.6 alpha heap-profiling pilot when a specific unresolved trace question justifies the extra diagnostic complexity.

No Gradle task, build, APK/AAB, profile collection, physical-device benchmark, workflow dispatch or CI was run.


### 16 November 2026 — phone-runner interruption guard

The uploaded failed-run diagnostics show Android launching `com.android.systemui/.wifi.WifiDebuggingActivity` through `AdbDebuggingManager.verifyWifiNetwork`; the final `dumpsys` snapshot reports LADB (`com.draco.ladb`) as the focused and top-resumed Activity. This is concrete evidence of a Wireless debugging authorisation interruption during the failed run. It does not, by itself, prove that every readiness timeout has the same cause.

The phone runner now:
- Captures actual `dumpsys window windows` focus and `dumpsys activity activities` resumed-Activity state immediately before and after each instrumentation run. These are separate from UiAutomator's last-accessibility-event package, which is not a foreground-window API.
- Detects the System UI `WifiDebuggingActivity` (or a System UI focused/resumed Activity) before starting instrumentation and stops with an actionable instruction to approve or dismiss the Wireless debugging prompt. It does not attempt to bypass Android's ADB security prompt, kill LADB, or continue with an unreliable measurement.
- Retains the original Home and fully-drawn readiness markers and all benchmark metrics. No app startup code or benchmark timing behavior was changed.
- Reports the foreground snapshot paths when instrumentation exits unsuccessfully.

A fixture test was added for the pending-confirmation case and for the foreground snapshots. **The test has not yet been executed in a local checkout**, because this session has repository API access but no working local clone. No build, benchmark run, or CI was started. The runner/test changes therefore need the shell test suite to be run from a checkout before they can be called verified. On the phone, approve the Wireless debugging prompt for LADB before rerunning; the new guard should now fail immediately with the reason instead of wasting the 30-second readiness timeout.


## 16 November 2026 — benchmark methodology review and latest research

### Current AndroidX release check

The official AndroidX Benchmark release page still lists **1.5.0 as stable** and **1.6.0-alpha01 (7 October 2026)** as the alpha line. There is no newer stable release to justify changing Nuvio's pinned androidx-benchmark = "1.5.0". The alpha's notable additions remain opt-in Perfetto heap profiling through MemoryProfilingConfig and broader fixed-performance-mode clock locking. Neither is a demonstrated improvement to this cold-start comparison on the user's non-rooted phone. Keep the stable dependency unchanged.

Sources: [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark), [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

### Benchmark implementation review

The current Java benchmark is structurally aligned with AndroidX's documented cold-start pattern:
- StartupMode.COLD is explicit, with pressHome() in setup and startActivityAndWait() in the measured block.
- The two arms are explicit: CompilationMode.None and CompilationMode.Partial(BaselineProfileMode.Require). The latter fails rather than silently benchmarking without the required profile.
- Each arm uses ten measured iterations and StartupTimingMetric, preserving both TTID and TTFD.
- StartupInsightsConfig(true) is enabled on the same capture, so the generated Perfetto traces can be examined for likely startup blockers.
- The test waits for the real Home route and the app's Compose fully-drawn marker; this prevents the timing result from treating an early shell/splash frame as usable Home.

No code change to these controls is justified by the latest primary documentation. Android Developers explicitly says to use TTID and TTFD together, focus on median values, and keep Compose's ReportDrawn / ReportDrawnWhen integration rather than manually calling reportFullyDrawn.

Sources: [Startup metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics), [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

### New independent evidence: optimization opportunities worth investigating, not copying

| Source | Reported result | Transferable lesson | Nuvio decision |
|---|---|---|---|
| Google Android Developers / Grab, 19 Oct 2026 | About 27% cold-start improvement from R8 optimization plus Baseline Profiles and Startup Profiles; a further ~10% from R8 Full Mode; cumulative ~35% | Keep-rule blockers can prevent R8 from optimizing app code even when minification is enabled | Highest-value next artifact is the R8 Configuration Analyzer report for each actual minified distribution variant. Do not alter keep rules without the report and contract review |
| Google Android Developers / Tinder, 18 Aug 2026 | 47% fewer users experiencing slow cold starts; 28.98% smaller download; 28% lower user-perceived ANR rate | A broad unscoped keep rule in an in-house library blocked optimization | Inspect Nuvio's analyzer output for broad rules and their origin before making any R8 change |
| Google Android Developers / Monzo (2026) | Reported cold starts 30% faster, warm 24%, hot 14%, and P50 launch 11% faster after enabling R8 Full Mode | Build optimizer configuration can produce larger gains than speculative runtime micro-optimizations | Check Nuvio's effective release shrinker configuration and analyzer report first |
| First-hand 2026 startup case studies | Deferring hidden heavy UI/module work can help TTID, but moving work to background threads can still contend with the UI thread and delay full readiness | A task being off-main-thread does not mean it is free during startup | Preserve Nuvio's TTID + Home-ready TTFD contract and inspect traces before deferring more work |

Sources: [Grab R8/profile optimization](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time), [Tinder R8 Configuration Analyzer](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer), [Monzo R8 Full Mode](https://developer.android.com/blog/posts/monzo-boosts-performance-metrics-by-up-to-35-with-a-simple-r8-update), [2026 field startup case study](https://shubhamsinghbisht.com/articles/android-app-start-25-percent-faster/).

### Statistical interpretation: ten iterations are a measurement set, not proof of a small win

AndroidX reports minimum, median and maximum and recommends the median as the typical-startup summary. Independent practitioner analysis of Macrobenchmark variability makes an additional important point: ten samples are not enough to claim that a small median movement is statistically meaningful, especially when run-to-run distributions shift with thermal state, background work and clock behavior. That analysis recommends checking distribution shape and variance and using confidence intervals when making formal claims; its 30+ sample guidance is a statistical methodology, not an AndroidX requirement.

For Nuvio's next authorized physical-device campaign:
1. Keep the current ten-iteration paired arms as the practical diagnostic pass.
2. Repeat the complete paired campaign in multiple sessions under the same device, OS, account, network and battery/thermal conditions. Do not compare one arm from one day with the other arm from another day.
3. Preserve per-iteration JSON samples and all Perfetto traces. Report each arm's TTID and TTFD median, range and coefficient of variation; flag unstable distributions rather than presenting a tiny median delta as a win.
4. Treat post-reboot and settled-device launches as separate cohorts. The existing /proc/uptime, battery and thermal snapshots are useful context, but they bracket the instrumentation invocation rather than every individual iteration.
5. Do not discard early iterations silently: AndroidX's cold-start and compilation controls already define the intended experiment. If a separate steady-state-after-boot cohort is desired, label and repeat it as a separate experiment.

Sources: [AndroidX startup metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics), [Statistically Rigorous Android Macrobenchmarks](https://blog.p-y.wtf/statistically-rigorous-android-macrobenchmarks), [Macrobenchmark comparison script and worked example](https://blog.p-y.wtf/a-script-to-compare-two-macrobenchmarks-runs).

### Concrete next improvement cycle

1. **R8 analyzer first:** run the documented standalone analyzer task for fullRelease and playstoreRelease only when a Gradle task is authorized. Review the generated report and trace each optimization blocker to its originating rule/library. Validate reflection, JNI, serialization and plugin contracts before narrowing any keep rule.
2. **Artifact proof second:** inspect the actual minified release-like APK/AAB for consumed Baseline Profile, Startup Profile metadata and primary DEX placement. A non-empty generated text profile alone does not prove that the measured artifact packaged and consumed the intended profile.
3. **Trace-led app changes third:** use Startup Insights and Perfetto to identify work before first frame and before the Home-ready signal. Change one proven bottleneck at a time, then rerun the same paired benchmark and check both TTID and TTFD.
4. **Do not add noise:** no manual cache clearing, forced clock locking on this non-rooted phone, blanket lazy initialization, extra background dispatch, or alpha-only metrics without a trace question and a validation plan.

No app source, benchmark parameters, dependency, profile, shrinker rule or build configuration was changed in this research cycle. The only implementation change in the preceding cycle remains the phone-runner Wireless debugging interruption guard. No build, Gradle task, device benchmark or CI was run.


## 16 November 2026 — CI regression-fixture root cause and correction

### Improvement cycle 1 — Keep instrumentation assertions with the run that emits them

The diagnostic run `43976620274` failed before APK compilation. The traced failure was not the package-manager recovery implementation:

- The injected `INSTALL_FAILED_UPDATE_INCOMPATIBLE` was observed by the phone-runner fixture.
- The runner removed only `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile`, retried the isolated app install, installed the matching test APK, resolved the self-instrumenting test component, and launched the benchmark app.
- The test later failed on its final `androidx.benchmark.enabledRules BaselineProfile` grep. The shared fake command log had already been deliberately truncated before the Wireless debugging prompt negative test. The profile-collection invocation from the earlier successful combined run was therefore no longer present.
- This was a test assertion lifetime/scope defect. The runner's signing-key recovery and protection of production `com.nuvio.media` were not implicated.

Correction on `main-build-testing`:
- Assert the Baseline Profile instrumentation invocation and Macrobenchmark instrumentation invocation immediately after each successful combined `run` journey, while the corresponding fake command log is still intact.
- Keep the final signing-recovery assertions scoped to package install/recovery, instrumentation resolution and benchmark-app launch.
- No phone-runner code, app startup code, benchmark parameters, build types, signing settings or dependencies were changed.

The previous diagnostic `set -x` is temporary and must not remain in the final test source.

### Improvement cycle 2 — Re-check what is worth changing in the benchmark itself

The current benchmark source remains aligned with the official cold-start comparison contract: release-like, R8-minified and profileable target APK; separate instrumentation APK; `StartupMode.COLD`; `CompilationMode.None` against `CompilationMode.Partial(BaselineProfileMode.Require)`; ten measured iterations per arm; `StartupTimingMetric`; and a Compose-driven fully-drawn signal plus the real Home-ready guard. Startup Insights is enabled for trace diagnosis. The phone runner retains per-iteration Macrobenchmark JSON and Perfetto traces, device/thermal/battery/uptime context, and explicit API-level handling.

Fresh external research checked against the pinned AndroidX version:
- AndroidX Benchmark **1.5.0 remains the stable dependency used here**. The 1.6.0-alpha01 line adds opt-in Perfetto heap profiling and broader fixed-performance-mode clock-lock support, but neither is a concrete improvement to this non-rooted phone's current cold-start experiment. Do not move the benchmark onto an alpha simply to appear newer. [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark)
- Android Developers defines TTID as the first displayed frame and TTFD as the app-reported fully-drawn state, recommends evaluating both, and says the median is the useful typical-startup summary. The current `ReportDrawnWhen` + Home-ready contract should remain; do not weaken it to make runs pass. [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- The official Baseline Profile measurement guidance uses a no-compilation comparison and a required Baseline Profile comparison, recommends physical devices, and warns that an emulator is not representative. Nuvio already follows that structure. [Measure Baseline Profile impact](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)
- An independent 3 August 2026 Pixel 7 Pro / Android 16 write-up measured ten cold starts with Macrobenchmark 1.4.1: median TTID 500.8 ms without the profile vs 427.1 ms with it (14.7%). This supports the value of testing the actual shipped profile, but is not an expected Nuvio gain. [Method and result](https://shaheer.blog/how-we-measured-our-android-cold-start-and-got-15-back-from-a-text-file/)
- Google's Android case studies show materially different gains by app and cohort, reinforcing that profile/R8 outcomes need app-specific measurement. Grab's R8 + Baseline/Startup Profile + R8 Full Mode work reports a cumulative ~35% cold-start improvement; Tinder documents a broad keep rule blocking optimization; Monzo reports gains from R8 Full Mode. These are reasons to inspect Nuvio's own R8 Configuration Analyzer reports, not to copy their percentages or alter keep rules speculatively. [Grab](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time) · [Tinder](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer) · [Monzo](https://developer.android.com/blog/posts/monzo-boosts-performance-metrics-by-up-to-35-with-a-simple-r8-update)

The exact AndroidX Benchmark dependency is pinned at 1.5.0 in `gradle/libs.versions.toml`. Context7 documentation lookup was used for the AndroidX Macrobenchmark `measureRepeated` Java API and compilation modes. No API or dependency change was justified.

### Verification boundary and next highest-value work

- GitHub Actions run `43976620274` failed in validation before Rust target setup, Gradle compilation or bundle assembly. It produced no new APK artifact.
- The test correction is committed on `main-build-testing` as `9ca5eb25da214cfe2cb96f54877477d11be47e47`; the canonical benchmark workflow remains manual-dispatch-only on that branch.
- The same corrected test, without diagnostic tracing, must pass on the isolated CI branch before the eight-APK bundle is built.
- Once packaging succeeds, the highest-value app/build investigation remains the standalone R8 Configuration Analyzer report for both actual minified distribution variants, followed by inspection of consumed Baseline/Startup Profile and primary DEX metadata. These are report/artifact-led checks; do not narrow keep rules or change profile filters without evidence.
- Keep the ten-iteration paired run as the practical diagnostic set, preserve all traces and per-run samples, and repeat complete paired sessions on the same physical phone. Treat settled-device and post-reboot conditions as separate cohorts. A small median delta from one ten-iteration session is not proof of a statistically significant Nuvio speedup.

No Nuvio app startup optimization, measured startup claim, physical-device benchmark, APK build or successful CI run is claimed by this entry.


### Improvement cycle 3 — Validate the correct runner for each contract

After the signing-recovery fixture passed, run `43978450085` reached `test-android-startup-metric-contract.sh` and exposed a second static-test wiring error:

- The contract test named its source variable `PHONE_RUNNER`, but assigned it to `scripts/run-android-startup-profile-benchmark.sh`, the desktop/ADB Gradle orchestrator.
- The earlier TTID/TTFD log assertions and per-flavor Gradle check-order assertions belong to that desktop script.
- The later per-JUnit-case status parsing, copied phone instrumentation artifacts, JSON/statistics validation, and phone device snapshots belong to `scripts/nuvio-phone-benchmark.sh`.
- The test mixed the two files under one variable, so it looked for phone-only functions in the desktop script. Both production scripts already had the expected behavior.

Correction on `main-build-testing`:
- Declare separate `DESKTOP_RUNNER` and `PHONE_RUNNER` paths and check that both files exist.
- Keep desktop log-emission and per-flavor TTID/TTFD validation-order assertions pointed at the ADB orchestrator.
- Point per-test JUnit status checks, JSON/trace validation, profile-output validation and phone environmental snapshot assertions at the LADB phone runner.
- Keep the final order assertion for `require_macrobenchmark_cases` → `copy_recent_outputs benchmark` → `require_macrobenchmark_result_data` specifically on the phone runner.

This is a test-only correction. Neither runner, benchmark test, APK configuration, app behavior or metric contract was weakened.

### R8 Configuration Analyzer follow-up — AGP capability confirmed

The repository pins AGP 9.4.0 and does not set `android.experimental.r8.enableR8ConfigurationAnalyzer=false` in `gradle.properties`. Android's current guidance confirms that AGP 9.3+ can run the standalone analyzer task and also emits `configanalyzer.html` automatically during ordinary R8 builds. The documented standalone report path is under the module's `build/reports/r8`; reports generated during R8 builds are under `build/outputs/mapping/<variant>/configanalyzer.html`.

For Nuvio, inspect the analyzer reports for the actual `fullBenchmarkRelease` and `playstoreBenchmarkRelease` variants after the bundle workflow completes, if the AGP build produces them. They are useful evidence about the same R8 rules used by these minified benchmark targets, but they are not a substitute for reports on production `fullRelease` and `playstoreRelease` if those variants differ. Do not add keep-rule edits based on a score alone; inspect the rule origin and runtime contracts first.

Sources: [R8 Configuration Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer), [AGP 9.3 standalone analyzer task](https://developer.android.com/build/releases/agp-9-3-0-release-notes).

Verification boundary:
- Run `43978450085` passed the full phone-runner shell regression, including the injected signing mismatch and all combined-run instrumentation assertions. It stopped at the separately wired desktop/phone static contract test, before Rust target setup or Gradle compilation.
- The runner-source split is committed on `main-build-testing` as `8ec0135ea6ca5554d0716574309199a41ab2d264`. It still requires the isolated CI branch to execute the corrected contract test.
- The R8 Analyzer report existence and exact variant output paths have not yet been confirmed by a completed build. No R8 rule change or performance improvement is claimed.


### Improvement cycle 4 — Align the static profile contract with the actual phone export

The next validation of the corrected runner-path split reached the phone-side assertions and identified another stale symbol in `test-android-startup-metric-contract.sh`: it required a function called `require_non_empty_baseline_profile`, while the phone runner deliberately uses `require_non_empty_startup_profile`.

The current phone `collect` action is specifically the raw Startup Profile export. The integration test already makes that contract explicit: it requires a non-empty `startup-prof.txt` and rejects copying a separate `baseline-prof*.txt` artifact during that raw Startup Profile collection. The separate R8 `benchmarkRelease` APKs are validated for the compiled Baseline Profile asset `assets/dexopt/baseline.prof`; this is a different artifact and a different part of the workflow.

Context7's AndroidX `BaselineProfileRule.collect` Java API confirms the current argument meanings: the two booleans in the generator call are `includeInStartupProfile` and `strictStability`. Both are set to true. The phone-runner validation should therefore assert the existing `require_non_empty_startup_profile()` function and ensure it executes after `copy_recent_outputs profile`. It must not invent a separate Baseline Profile text-file requirement for that raw Startup Profile export.

Correction on `main-build-testing`:
- Replaced the three stale `require_non_empty_baseline_profile` symbol checks in the static contract test with `require_non_empty_startup_profile`.
- Updated the associated failure diagnostics to identify Startup Profile validation.
- Left the runtime phone runner and the integration test's explicit Startup Profile-only output behavior unchanged.

This is another validation-only repair; it does not alter either benchmark arm or the packaged Baseline Profile requirement.

The corrected metric-contract test is committed on `main-build-testing` as `471922ebc2d4ddfee146bdeaddfcc344287cf891`. It still needs a full isolated workflow validation. No APK build has yet passed the pre-build validation gate.


## 17 November 2026 — cancelled long build, CI efficiency and diagnostic capture

### Latest build evidence: run 43979670892

GitHub run [43979670892](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43979670892), commit `956327cc1a3c965e9cbb8f333c279a71be81568f`, completed the pre-build validation and Rust setup. The full profile-capture pair, Play Store profile-capture pair and full R8 benchmark pair all completed successfully. GitHub marked the final Play Store R8 benchmark build step **cancelled**; the bundle-assembly and artifact-upload steps were skipped.

The job's configured timeout is 150 minutes; the run ended after approximately 62 minutes, so this is not the job timeout. No newer run on the same branch was present in the run history, and the completed job-log archive currently returns `BlobNotFound`. Consequently the available evidence does **not** identify who or what cancelled the Gradle step, and does not provide a Gradle compiler error. Do not describe it as an established Play Store source/build failure. No artifact was uploaded.

The workflow previously used `cancel-in-progress: true`. That setting can cancel an active run when another run for the same concurrency group is queued, although no such overlapping run was seen in this case. The setting is now false so a subsequent push will not automatically kill a long-running benchmark job. This is a forward-looking protection, not a claimed explanation for the previous cancellation.

### Improvement cycle 1 — Align Rust setup and caches with the APK's actual ABI

The Android build configuration's `buildsArm64BenchmarkApks` branch sets the Rust build task to `listOf("arm64-v8a")` whenever a `BenchmarkRelease` or `NonMinifiedRelease` assembly is requested. The APK bundle workflow also rejects native APK contents other than `arm64-v8a`. The CI setup nevertheless installed four Rust standard-library targets.

Correction:
- Install only `aarch64-linux-android` for this phone-only bundle.
- Cache the Cargo registry/Git dependency stores, the native workspace `native/target/` output, and the `cargo-ndk` executable using `actions/cache@v6`.
- Pin `cargo-ndk` at the latest stable published release observed, `4.1.2` (9 August 2025), using `--locked`. The project Cargo lockfile remains the source of truth for the native workspace dependencies.
- Include Rust compiler fingerprint, NDK version, lockfile and native source/manifest hashes in the native target cache key, with a same-toolchain fallback key for incremental reuse.

This removes installation of three unused cross-compilation targets and avoids reinstalling the same Cargo tool on every ephemeral runner. It does not change the native crate, NDK, ABI, Rust optimization profile, or packaged native libraries.

### Improvement cycle 2 — Build both variants together per distribution

`composeApp/build.gradle.kts` derives the Android source set from the requested Gradle task names and explicitly rejects a single Gradle invocation that mixes `full` and `playstore` distributions. It is safe to batch both build types within one distribution, because all requested tasks then resolve to exactly one source set.

The workflow now uses two Gradle invocations:
- Full: `fullNonMinifiedRelease` app/test pair plus `fullBenchmarkRelease` app/test pair.
- Play Store: `playstoreNonMinifiedRelease` app/test pair plus `playstoreBenchmarkRelease` app/test pair.

Each invocation requests the app and self-instrumenting test APKs for both build types. This allows Gradle to form one shared task graph per distribution instead of starting four separate Gradle invocations. Full and Play Store remain separate, preserving the distribution source-set guard. The APK names, variant build types, package IDs, signing setup, and eight-APK output contract remain unchanged.

Both flavor invocations also enable Gradle's `--profile` report, preserve the console output, and record elapsed seconds plus the Gradle exit status. This gives us actual phase/task timing evidence in the next run rather than inferring the bottleneck from wall-clock duration alone.

### Improvement cycle 3 — Preserve R8 and Gradle diagnostics independently of phone bundle assembly

Nuvio pins AGP 9.4.0, which is above the AGP 9.3 minimum for the R8 Configuration Analyzer. Official AGP documentation states that a regular R8 build automatically emits `configanalyzer.html` under `build/outputs/mapping/<variant>/`; the standalone `analyze<Variant>R8Config` task is also available, but is unnecessary to obtain the reports from the existing minified benchmark builds.

The workflow now collects, when present:
- `androidApp/build/outputs/mapping/fullBenchmarkRelease/configanalyzer.html`
- `androidApp/build/outputs/mapping/playstoreBenchmarkRelease/configanalyzer.html`
- Gradle `--profile` HTML reports
- Both per-distribution Gradle console logs and build timing records

These are uploaded as a separate always-attempted diagnostics artifact. They are **not** added to the phone bundle, whose contract remains eight APKs, two scripts, `START-HERE.txt` and `SHA256SUMS.txt` (12 files total). That keeps phone installation instructions and checksum behavior unchanged while preserving evidence for the next R8/CI optimization review.

Official source: [R8 Configuration Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer).

### Current benchmark/research decision

No change is justified to the startup benchmark's pinned AndroidX Benchmark 1.5.0, its ten-iteration paired cold-start cases, `CompilationMode.None()` versus `CompilationMode.Partial(BaselineProfileMode.Require)`, the real Home-ready/Compose fully-drawn contract, or its TTID/TTFD and Perfetto trace requirements. AndroidX 1.6.0 remains an alpha line in the current release notes; its optional heap profiling is a separate diagnostic pass, not a reason to move the timing baseline to an alpha dependency. [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark).

Current Google case studies continue to support inspecting R8 rules and real profile consumption before inventing startup-code changes:
- Grab reports roughly 35% cumulative cold-start improvement across R8, Baseline/Startup Profiles, Full Mode and keep-rule refinement; the report describes several separate phases, not a transferable Nuvio target. [Grab](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time)
- Tinder reports that a broad keep rule blocked R8 optimization and that targeted refinement coincided with 47% fewer users experiencing slow cold starts. [Tinder](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer)
- Monzo's R8 Full Mode work reports cold, warm and hot start improvements, but its initial configuration differed from Nuvio's existing `proguard-android-optimize.txt`; copying that change would therefore not be justified here. [Monzo](https://developer.android.com/blog/posts/monzo-boosts-performance-metrics-by-up-to-35-with-a-simple-r8-update)

The next concrete R8 review is to inspect Nuvio's two generated Configuration Analyzer reports for broad keep rules and trace each warning to its reflection, serialization, dynamic plugin, JNI or media contract before changing any rule. No keep rule has been altered, and no Nuvio startup improvement is claimed.

### Verification boundary

The modified canonical workflow remains `workflow_dispatch` only; source pushes on `main-build-testing` do not start the bundle build. The isolated CI branch runner remains the authorized push-triggered test route. The new bundle contract test checks the arm64-only target, caches, pinned cargo-ndk, grouped Gradle invocations, preserved diagnostic reports and separate diagnostics artifact, while retaining the existing APK and checksum validation.

The corrected pre-build test suite and the optimized APK workflow still need to run together on the isolated branch. No new APK bundle or physical-device Macrobenchmark result is available yet.


### Improvement cycle 4 — Fix the bundle-contract workflow reference and cache-key transport

The next validation run, [43992388394](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43992388394), again passed the full phone-runner regression suite, then failed its new concurrency assertion before any APK build. The contract test inspects the canonical `android-startup-profile-benchmark.yml`; on the isolated CI branch that copy still retained the earlier `cancel-in-progress: true`, even though the active CI runner workflow had been changed to false. The correction is to keep both workflow copies consistent. The run also proved the diagnostics collector and separate upload step execute after a pre-build validation failure: diagnostics artifact 14288506595 was uploaded (373 bytes, containing the status/timing placeholders).

A review of GitHub's official contexts documentation found a second configuration detail before relying on the new caches: values written to `GITHUB_ENV` are available to later shell processes, but are not part of the workflow-expression `env` context used to build an `actions/cache` key. The initial cache configuration incorrectly referenced that shell-only value in `steps.cache.with.key`.

Correction:
- Give the Rust setup step the ID `rust-toolchain` and write the compiler fingerprint to `GITHUB_OUTPUT`.
- Reference `steps.rust-toolchain.outputs.cache-key` in the native target cache key.
- Keep a fallback prefix for the earlier empty-fingerprint cache key, so the already-created target cache can be reused and saved under the corrected key on a subsequent run.
- Key the pinned `cargo-ndk` executable by its own exact version (4.1.2), with a compatible restore prefix; its executable does not need to share the native crate's compiler-version key.
- Add regression assertions to reject future uses of the runner-only `GITHUB_ENV` variable in cache key expressions.

The same build optimization remains in place: arm64-only Rust target installation, Cargo registry/target/binary caching, two Gradle invocations (one per distribution), Gradle profile logs/timings, and a separate diagnostic artifact. No app source, benchmark parameters, profile, NDK version or R8 keep rule changed.


## 17 November 2026 — hosted-runner loss during Play Store build; CI resource correction

Run 43993311068 completed with failure at 02:05:04 UTC. GitHub's check-run annotation says: "The hosted runner lost communication with the server. Anything in your workflow that terminates the runner process, starves it for CPU/Memory, or blocks its network access can cause this error." The full-distribution build succeeded from 01:11:40–01:20:13 UTC (8m33s). The Play Store Gradle step began at 01:20:13 UTC and remained in_progress when the runner disappeared 44m51s later. Diagnostics upload and phone-bundle assembly never ran; the run has zero artifacts and the job logs return BlobNotFound. This is not a recorded Gradle/Kotlin compiler or R8 error; the exact runner termination cause cannot be proven from surviving metadata.

The workflow had inherited developer JVM maxima of 12 GiB for the Gradle daemon and 8 GiB for the separate Kotlin compiler daemon. The repository is public and GitHub documents standard public ubuntu-latest runners as 4 CPU / 16 GB RAM / 14 GB SSD. The combined 20 GiB heap ceilings exceed physical RAM before metaspace, native JVM memory, Android tools and the OS. This is a concrete resource-budget risk, not proof this attempt was OOM-killed. Disk exhaustion and transient hosted-runner/network failure remain possible.

Workflow-only mitigation: apply Gradle -Xmx6g and Kotlin daemon -Xmx3g, each with 512 MiB initial heap and 1 GiB metaspace caps, and set --max-workers=2. Keep workstation gradle.properties and app source unchanged. Gradle's command-line -Dorg.gradle.jvmargs overrides the daemon JVM property; Kotlin's -Pkotlin.daemon.jvmargs overrides the independent daemon's project property. The 6/3 GiB split is a conservative CI budget, not a measured Nuvio optimum.

Recoverability improvements: capture CPU count, free memory, filesystem capacity/inodes, cgroup memory current/max/events and top resident processes before, every 45 seconds during, and after each distribution Gradle group. Stream the snapshots to the job log and save them in the diagnostics directory. Upload the full-distribution log/resource/timing checkpoint before starting the Play Store phase. Keep final R8 Analyzer and Gradle profile collection unchanged.

Research context: Iñaki Villar's 28 March 2026 Process Watcher case study measured Gradle and Kotlin daemon RSS separately on a 16 GB GitHub runner; after testing multiple splits, 7 GiB Gradle / 3 GiB Kotlin was stable in that specific sample. His 21 March 2025 Now in Android experiment found no meaningful build-time benefit from increasing Gradle heap without limit; 4–5 GiB was a better balance in that sample. Neither result is a guarantee for Nuvio. GitHub runner-images issue #15267 documented missing logs and unfinished step states reproduced by filling a hosted runner's root disk; its reproduction required a 17.93 GB checkout and does not prove Nuvio's smaller checkout exhausted storage.

References:
- [GitHub-hosted runner hardware specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Kotlin daemon and Gradle compilation/cache documentation](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)
- [Gradle build environment and JVM configuration](https://docs.gradle.org/current/userguide/build_environment.html)
- [Using RSS to Understand Memory Pressure in CI Builds — 28 March 2026](https://dev.to/cdsap/using-rss-to-understand-memory-pressure-in-ci-builds-1358)
- [Balancing Memory Heap and Performance in Gradle Builds — 21 March 2025](https://dev.to/cdsap/balancing-memory-heap-and-performance-in-gradle-builds-454j)
- [GitHub runner-images issue #15267](https://github.com/actions/runner-images/issues/15267)

No app source, startup metrics, ten-iteration comparison, Baseline Profile, R8 rules, dependencies or production Gradle settings changed; no startup speedup is claimed. Next bundle verification must complete both flavors and produce the eight APKs and checksum manifest.


## 17 November 2026 — confirmed Kotlin compiler heap failure and restoration of the known-good build sequence

### Run 44003964825: actual failure from job logs and Gradle profile

The complete GitHub Actions log and uploaded diagnostics artifact identify the failure precisely. Task `:composeApp:compileAndroidMain` threw `java.lang.OutOfMemoryError: Java heap space`, surfaced as Kotlin's `OOMErrorException`. The Gradle profile reports 1h20m33s total build time. The Kotlin daemon was constrained to `-Xmx3g`; its observed RSS was approximately 3.5 GiB, while the Gradle process was approximately 4.6 GiB RSS. Resource snapshots still showed around 6.6–6.9 GiB available memory and 81 GiB free disk. This was a Kotlin compiler heap limit failure, not disk exhaustion or an observed host-wide OOM.

The failed workflow combined both `NonMinifiedRelease` and `BenchmarkRelease` tasks for the full distribution into one Gradle invocation. The failure occurred at the shared Compose Multiplatform Android compile task. The combination increased the size/shape of the task graph, but the evidence does not isolate grouping as an independent cause; the explicit 3 GiB Kotlin heap limit is the direct failure.

### Proven successful reference: run 43895616216 / commit 71cc598

The successful screenshot corresponds to [run 43895616216](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43895616216), completed in 35m43s. Its logs confirm four separate Gradle invocations, all successful:

1. Full `NonMinifiedRelease` profile-capture pair: 11m35s.
2. Play Store `NonMinifiedRelease` profile-capture pair: 4m52s.
3. Full `BenchmarkRelease` R8 pair: 8m27s.
4. Play Store `BenchmarkRelease` R8 pair: 6m49s.

The workflow used repository JVM settings of Gradle `-Xmx12288M` and Kotlin daemon `-Xmx8192M`. The complete 12-file phone bundle was assembled, checksummed and uploaded. This is the strongest available known-good reference, so the workflow is being returned to its four-phase sequence rather than retaining the unproven two-invocation optimization.

### Corrective change

- Restore the four separate flavor/build-type Gradle invocations from the successful run.
- Raise the workflow-only Kotlin daemon heap from 3 GiB to 6 GiB. Keep Gradle at 6 GiB and workers at two as a bounded CI budget; do not alter developer `gradle.properties`.
- Keep per-phase logs, Gradle profiles, resource snapshots and the early full-profile checkpoint.
- Preserve all eight APKs, two scripts, START-HERE instructions, APK/package/profile/signing checks, SHA256 manifest and 12-file bundle contract.
- Keep the canonical workflow manual-only; the isolated CI branch remains the push-triggered test route.

Kotlin's official documentation confirms that the Kotlin daemon has a separate heap from Gradle and that `kotlin.daemon.jvmargs` controls it. JetBrains' October 2026 support note documents heap exhaustion while compiler metadata is loaded and demonstrates a 5 GiB Kotlin daemon heap. These sources support correcting the compiler heap specifically; they do not claim 6 GiB is universally optimal.

Sources:
- [Kotlin daemon](https://kotlinlang.org/docs/kotlin-daemon.html)
- [Kotlin compilation and caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)
- [JetBrains: Kotlin compilation fails with OutOfMemoryError](https://support.jetbrains.com/hc/en-us/articles/40252810066066-Kotlin-compilation-fails-with-OutOfMemoryError-GC-overhead-limit-exceeded)
- [Kotlin Multiplatform community discussion of compiler heap exhaustion](https://slack-chats.kotlinlang.org/t/34059118/hey-guys-has-anyone-faced-java-lang-outofmemoryerror-java-he)

No application source, runtime behavior, benchmark iterations/metrics, Baseline Profile, R8 rules, dependencies or production Gradle settings changed. No startup-performance improvement is claimed. The next authorized build should validate all four phases and produce the 12-file phone bundle.


## 17 November 2026 — confirmed Kotlin compiler heap failure and restoration of the known-good build sequence

Run [44003964825](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44003964825) failed specifically in `:composeApp:compileAndroidMain` with `java.lang.OutOfMemoryError: Java heap space`, surfaced as Kotlin `OOMErrorException`. Its Gradle profile records 1h20m33s. The Kotlin daemon was capped at `-Xmx3g` and reached about 3.5 GiB RSS; Gradle was about 4.6 GiB RSS. At the same time the runner reported 6.6–6.9 GiB available memory and 81 GiB free disk. The direct failure was the compiler daemon's own heap limit, not disk exhaustion or an observed host-wide OOM.

The failed workflow grouped both non-minified profile-capture and R8 benchmark tasks into one full-distribution invocation. The failure occurred at the shared Compose Multiplatform Android compile task. Grouping may have increased task-graph memory pressure, but the evidence does not prove grouping alone caused the failure; the 3 GiB Kotlin cap is the direct error.

The known-good reference, [run 43895616216](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43895616216), completed in 35m43s and used four separate Gradle invocations: full profile-capture (11m35s), Play Store profile-capture (4m52s), full R8 (8m27s), and Play Store R8 (6m49s). It used the repository's Gradle 12 GiB / Kotlin 8 GiB JVM maxima and produced the complete 12-file phone bundle. The workflow is restored to these four isolated phases.

Correction: restore four Gradle invocations; raise only the workflow Kotlin daemon heap from 3 GiB to 6 GiB; keep Gradle at 6 GiB and two workers; preserve phase logs, profiles, resource snapshots and early checkpoint; retain all APK/package/profile/signing validations and the 12-file checksum bundle. Developer `gradle.properties`, application source, runtime behavior, benchmark metrics, Baseline Profile and R8 rules remain unchanged. The canonical workflow remains manual-only.

Kotlin documents that the compiler daemon is a separate JVM and that `kotlin.daemon.jvmargs` controls its heap. JetBrains' October 2026 support note describes heap exhaustion during compiler metadata loading and demonstrates a 5 GiB daemon heap. Sources: [Kotlin daemon](https://kotlinlang.org/docs/kotlin-daemon.html); [Kotlin compilation and caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html); [JetBrains compiler OOM guidance](https://support.jetbrains.com/hc/en-us/articles/40252810066066-Kotlin-compilation-fails-with-OutOfMemoryError-GC-overhead-limit-exceeded); [Kotlin Multiplatform community discussion](https://slack-chats.kotlinlang.org/t/34059118/hey-guys-has-anyone-faced-java-lang-outofmemoryerror-java-he).

No startup-performance improvement is claimed. The next authorized run must validate all four phases and generate the phone bundle.


## 17 November 2026 — LADB / on-device shell reliability review

### Confirmed command model

LADB is an ADB shell client over Android Wireless Debugging, not a desktop ADB executable. The phone runner correctly uses shell-native commands (`pm`, `am`, `getprop`, `dumpsys`, `settings`) and is invoked as `sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh ...`. Do not prefix the command with `adb shell`, and do not try to run desktop commands such as `adb install` inside LADB. The LADB README documents that its terminal is already an ADB shell; a Samsung GalaxyMaxHz support thread records the exact “permission denied / failed to open” mistake caused by using LADB's non-ADB-shell mode instead of its Pair and shell mode.

### LADB community reports and what they do / do not prove

- The official LADB README says assisted pairing can be sensitive to the Settings dialog closing, recommends keeping Settings and LADB in split-screen during pairing, documents incompatibility with Shizuku, and recommends clearing LADB app data, removing Wireless Debugging pairings and rebooting as a recovery path. This is LADB-specific maintainer guidance.
- LADB issue #120 records a June 2024 connection regression in LADB 2.4.x on Pixel 7 Pro / Android 14 and Pixel 8 Pro / Android 15 beta; one Samsung S24 user also reported it. This is version-specific and does not establish a current universal defect. It does mean a failed LADB transport must not be misdiagnosed as a Macrobenchmark failure.
- A Galaxy S23 / One UI 6 LADB support discussion identified the non-ADB-shell mode as the cause of install permission errors; the fix was enabling “Pair and shell” and completing pairing.
- Recent Samsung One UI 8/8.5 Shizuku reports describe Wireless Debugging or the Shizuku service stopping after screen lock or after roughly a minute, even with battery optimization relaxed. These are reports about Shizuku, not proof that LADB itself or this benchmark script is broken. They nevertheless establish that Samsung wireless-debugging stability can be device/firmware dependent and that foregrounding an app alone is not a universal guarantee.
- Community workarounds such as changing USB configuration, using unofficial Shizuku forks, or disabling process limits are not adopted as benchmark requirements. They are not verified LADB fixes and can change device state in ways that are inappropriate for a controlled performance measurement.

### Existing runner protections inspected

The current phone script already:
- requires a physical arm64 device on API 33+;
- stages only the matching APK pair into `/data/local/tmp/NuvioBenchmark`, avoiding Package Manager reading APKs directly from Downloads;
- invokes `pm install` and `am instrument` as commands within the already-open shell;
- resolves the actual self-instrumenting test component via `pm list instrumentation`;
- rejects a foreground Wireless Debugging confirmation dialog before instrumentation;
- checks instrumentation exit status and failure markers;
- validates both Macrobenchmark comparison cases, ten samples per metric, the JSON summary and all 20 matching Perfetto traces;
- writes environment and foreground snapshots around measurement and copies output from the instrumentation test package's external media directory.

The latter output location matches AndroidX Macrobenchmark's documented default: test APK external media storage. AndroidX's CI guide also documents that direct shell instrumentation is run with `am instrument`, and that Macrobenchmark results/traces are written to the device and must be collected manually when not using Gradle-connected tests. We retain the explicit test-package media path rather than changing it speculatively.

### Operational checklist for the LADB session

1. Open LADB in its ADB-shell / Pair and shell mode and confirm the Wireless Debugging connection is active. Complete pairing while the Settings pairing dialog remains open as the LADB README instructs.
2. Do not run Shizuku simultaneously with LADB; LADB's maintainer explicitly documents this incompatibility.
3. Keep LADB in the foreground for the entire profile-collection and measurement command. Keep the phone awake and unlocked; do not switch Wi-Fi networks, enable airplane mode, reboot, or enter power-saving modes during a run.
4. Keep the phone on stable Wi-Fi and power, allow it to cool before starting, and avoid Android Auto / USB mode changes during the session.
5. Extract the entire phone bundle together. Start with the documented `install-profile full` command, finish sign-in/onboarding and leave the benchmark app on Home, then run the matching collection/measurement command. Repeat for Play Store only after Full is complete.
6. If LADB itself disconnects, stop there and re-establish the shell before rerunning. Do not interpret a lost LADB transport as a benchmark result and do not repeatedly launch a second instrumentation run over an unknown first run.

### Reliability boundary and next code decision

No code can guarantee that a third-party LADB app, Samsung's Wireless Debugging service, Wi-Fi or Android's process manager will remain connected for the duration of a long command. The script can reject known-bad preconditions and report actual instrumentation failures, but it cannot transparently reconnect an in-flight `am instrument` process through a shell client whose transport has vanished.

No additional phone-runner code change was made in this review: the researched LADB-specific failures are primarily pairing / shell-mode / transport lifecycle issues, and no reproducible failure in the current runner's ADB-shell command syntax was found. Adding unverified keepalive loops or changing Android debugging settings would risk masking a disconnected session or contaminating benchmark conditions.

References:
- [LADB official README and troubleshooting](https://github.com/tytydraco/LADB)
- [LADB issue #120 — connection regression reports](https://github.com/tytydraco/LADB/issues/120)
- [GalaxyMaxHz discussion #138 — LADB non-ADB-shell permission error](https://github.com/tribalfs/GalaxyMaxHzPub/discussions/138)
- [LADB XDA discussion and ADB-shell command syntax](https://xdaforums.com/t/say-hello-to-ladb-a-local-adb-shell-without-needing-a-computer-or-root.4204855/)
- [Shizuku discussion #2249 — One UI 8.5 wireless-debugging stability reports (Shizuku-specific)](https://github.com/RikkaApps/Shizuku/discussions/2249)
- [Android benchmark in CI: shell instrumentation and on-device output paths](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
- [AndroidX ADB shell protocol](https://android.googlesource.com/platform/packages/modules/adb/blob/main/client/commandline.cpp)

The current workflow correction remains separate from phone-side LADB transport. The pending CI run #12 has not yet assigned a job; no additional build or CI run was started in this review.


## 17 November 2026 — stale CI branch and queued benchmark runs

### Incident evidence

The isolated branch `ci/main-build-testing-startup-bundle-20261116` was pushed without first reconciling it with the current `main-build-testing` branch. GitHub's compare endpoint reported the branches as diverged: 14 commits ahead and 14 commits behind, with merge base `493f93b15b54afdb2f1aba0c61bd001b798a3945`. At inspection, `main-build-testing` was at `6a0b89478687443c9944117b5b9f63ad36c714b3`, while the CI branch head was `697bcc4a14de9c8ebc0ec9de2c2a324b17cda865`. Therefore a CI run from that branch was not a build of the latest `main-build-testing` source.

Two runs were visible on the isolated branch at 04:21 UTC:
- [Run 44014620882 / run #9](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44014620882), commit `f00cb05a55307c6b973f7bad5cdbefe0572515c1`, was still in progress in the Play Store profile-capture Gradle phase. Its Full profile-capture APK pair had completed and the diagnostic checkpoint had uploaded.
- [Run 44014834553 / run #12](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44014834553), commit `697bcc4a14de9c8ebc0ec9de2c2a324b17cda865`, was pending with zero jobs assigned. Both use the same branch concurrency group; the observed active-plus-pending state is consistent with run #12 waiting behind run #9, not a Gradle failure in run #12.

No completion, cancellation or phone-bundle artifact is claimed for either run in this record. The run state must be rechecked before any further CI dispatch.

### Required safeguards before the next build

1. **Start from the actual current source branch.** Read the live `main-build-testing` HEAD immediately before preparing CI. Do not reuse an old CI branch merely because it contains benchmark changes.
2. **Prove branch freshness before any push.** The CI branch must contain the exact current `main-build-testing` HEAD as an ancestor; compare must report `behind_by = 0`. Record both branch HEAD SHAs and the merge-base SHA in the work note. If behind, reconcile the benchmark-only workflow/test/documentation deltas on top of the latest source. Do not force-push or blindly merge a diverged branch.
3. **Add a fail-fast workflow guard.** Before Gradle, fetch `main-build-testing` and verify its current tip is an ancestor of the checked-out CI commit. Stop before expensive work if the branch is stale. This guard is not yet installed in the isolated CI workflow.
4. **Check Actions state before pushing.** Inspect all queued, pending and in-progress runs for the isolated branch and its concurrency group. Do not push documentation-only changes or repeated fixes to a push-triggered CI branch; each push can queue another full build.
5. **Keep the canonical workflow manual-only.** `.github/workflows/android-startup-profile-benchmark.yml` on `main-build-testing` must retain `workflow_dispatch` as its only trigger. Use a separately named, fresh CI branch for the authorized bundle run.
6. **Verify the run's source SHA.** Before treating a run as the bundle build, confirm its `head_sha` is the intended fresh CI commit and that the freshness guard passed. After completion, verify all eight APKs, both scripts, `START-HERE.txt`, `SHA256SUMS.txt`, and the uploaded artifact contents.

At this inspection, the available GitHub integration did not expose an Actions run-cancellation operation. The two run links above remain the authoritative place to confirm and, where required, cancel the outstanding runs before another CI run is started. No new run was dispatched as part of this note update.

### Benchmark/source integrity decision

This incident does not justify changing app startup code, benchmark iterations, compilation modes, metrics, profile contents, R8 rules, dependency versions or device-side LADB commands. The existing benchmark contract remains unchanged. The corrective work is branch freshness, CI queue discipline, and a pre-build stale-source guard—not an unmeasured application or benchmark modification.


### Cancellation follow-up — verified final state

A fresh Actions read at 04:23:45 UTC confirmed both outstanding runs reached terminal `completed / cancelled` status:
- Run #9 `44014620882` was cancelled during `Build full R8 benchmark APK pair`. The two profile-capture APK pairs had completed. It uploaded a diagnostics artifact (`14298533732`) and the Full profile-capture checkpoint (`14298473881`); it did **not** assemble or upload the phone bundle.
- Run #12 `44014834553` was cancelled while pending, before any job was assigned. It has no artifacts.

This follow-up records the observed GitHub state only; it does not attribute the cancellation to a particular actor. The stale branch remains divergent and is not a valid source for another build.


## 17 November 2026 — stale CI branch / pending-run incident and prevention

### Incident facts

The isolated bundle branch `ci/main-build-testing-startup-bundle-20261116` was pushed while it had diverged from `main-build-testing`. The GitHub compare at the time reported 14 commits ahead and 14 commits behind; the CI run therefore used branch HEAD `697bcc4a14de9c8ebc0ec9de2c2a324b17cda865`, not the current `main-build-testing` HEAD `6a0b89478687443c9944117b5b9f63ad36c714b3`. This is an avoidable source-integrity failure. Do not use that CI run as validation of current main-build-testing source.

The last checked Actions metadata showed run #9 (`44014620882`) in progress and run #12 (`44014834553`) pending with zero jobs assigned. The available GitHub integration in this session exposed read-only Actions run/job operations and retry operations, but no cancel-workflow-run action. The cancellation request therefore could not be executed through the connected integration; no cancellation is claimed. Verify live run state and cancel stale runs through GitHub Actions UI or an authorized Actions API client before any new benchmark workflow is started.

### Mandatory prevention gate before every bundle build

1. Fetch the live `main-build-testing` branch head immediately before preparing the CI branch. Record the exact SHA.
2. The CI branch must be based on that exact current main SHA. Do not push a previously created CI branch merely because it contains benchmark workflow changes.
3. Compare both branches and inspect every unique commit and changed path. A non-zero ahead/behind count is a stop condition until the divergence is deliberately reconciled and reviewed. Never force-push to hide divergence.
4. Verify that the CI branch contains the current app source, Gradle wrapper, version catalog, benchmark sources, runner scripts, tests and current engineering note. Compare exact file content/SHA for the benchmark-critical files.
5. Check all active, queued and pending Actions runs for the same workflow/branch/concurrency group. Do not start another run while an old or ambiguous run remains active or queued. Cancel stale runs only through a confirmed Actions write-capable operation and verify their terminal `cancelled` state afterward.
6. Confirm the workflow's resolved `head_sha` equals the intended current source SHA before allowing Gradle steps to begin. The canonical `main-build-testing` workflow must remain `workflow_dispatch` only.
7. Only after all gates pass, trigger one build. Verify the completed run's SHA, all four Gradle phases, artifact contents (8 APKs + 2 scripts + START-HERE + checksum manifest), and checksum verification before calling the bundle ready.

### No speculative benchmark changes

This incident is a branch/CI orchestration defect, not evidence that startup measurement code or app runtime should change. Do not alter StartupTimingMetric, cold-start mode, ten measured iterations, BaselineProfileMode.Require, Startup Insights, readiness markers, generated profile rules, R8 rules, or production Gradle settings to compensate for stale source or queued jobs. Keep changes limited to proven workflow/branch integrity issues and failures supported by run logs or deterministic regression tests.


## 17 November 2026 — fresh-source CI failures and R8 mapping investigation

### Run #13: stale checkpoint assertion (pre-build)

[Run 44018086908](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44018086908), commit `1aa29985b6610e43c7d8280c6357bacaef5da785`, passed the new main-branch freshness guard. It failed in the pre-build shell validation before any Gradle assembly. The exact assertion expected the obsolete workflow step name `Upload full-distribution build checkpoint`; the workflow's actual step is `Upload full profile-capture checkpoint`. The `INSTALL_FAILED_UPDATE_INCOMPATIBLE` line was emitted by the phone-runner test's simulated package-manager response and was followed by its PASS message; it was not the failing assertion.

The follow-up branch commit `28f9b0b9aa923b3ed8491ac2a51f448c48395212` changed that single test expectation. Run #14 then passed the complete shell validation, confirming that specific stale assertion was corrected.

### Run #14: APK assembly succeeds; bundle validation rejects missing R8 mapping

[Run 44018520176](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44018520176), commit `28f9b0b9aa923b3ed8491ac2a51f448c48395212`, passed:
- source freshness guard;
- all phone-runner, bundle-assembly, readiness and metric-contract shell tests;
- Rust setup and native caches/install;
- Full and Play Store nonMinifiedRelease app/test APK build groups;
- Full and Play Store benchmarkRelease app/test APK build groups;
- diagnostics upload.

All four Gradle invocations ended with `BUILD SUCCESSFUL`. The final bundle assembly validated the APK package IDs, non-debuggable state and profileable shell manifest before stopping at the first R8 artifact assertion:

`full benchmarkRelease has no non-empty R8 mapping file; refusing to label it an R8 benchmark.`

The run did not upload the phone bundle. It did upload:
- diagnostics artifact [14300827886](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44018520176/artifacts/14300827886), containing four Gradle logs, four runner resource snapshots, four Gradle profiling reports, timings and R8 report status;
- Full profile-capture checkpoint [14300506825](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44018520176/artifacts/14300506825).

The workflow currently expects the mapping at `androidApp/build/outputs/mapping/fullBenchmarkRelease/mapping.txt` and `.../playstoreBenchmarkRelease/mapping.txt`. The first missing file is the observed blocker. Do not remove this assertion or relax the R8 benchmark label: that would risk shipping an unminified APK as the R8 measurement target.

### Evidence-based next investigation — do not guess at the mapping path

The run's surviving console log confirms the four Gradle groups succeeded and the benchmark APK passed package-ID, debuggable and profileable checks. It does not show any R8 minify task names in the console output. That alone is not sufficient to conclude why the mapping output is absent. The diagnostics artifact contains the complete per-phase Gradle logs and should be examined for:
1. resolved `fullBenchmarkRelease` and `playstoreBenchmarkRelease` optimization/minification state;
2. whether the R8 task was scheduled/executed for either variant;
3. the actual mapping artifact provider/output path, including any AGP 9.4 output relocation;
4. whether Sentry's mapping integration or another artifact transform consumes/relocates the mapping output.

Relevant current-version research:
- Nuvio pins AGP `9.4.0`, Gradle `9.8.2`, and Baseline Profile/Macrobenchmark `1.5.0`.
- Google issue [556343682](https://issuetracker.google.com/issues/556343682) documents an AGP 9.3.0 defect where the new `optimization { enable = true }` DSL did not propagate `ApplicationVariant.isMinifyEnabled`; Google lists fixes in AGP 9.3.3 and AGP 9.4.1. Nuvio currently pins 9.4.0. This is a relevant compatibility lead, not yet proven to be the cause of this mapping failure because the current app build script uses the legacy `isMinifyEnabled` DSL and the exact variant state/output has not been extracted from the diagnostics artifact.
- Google's [AGP optimization guide](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) says the new `optimization { enable = true }` DSL is available from AGP 9.3 and that the legacy DSL remains supported. Do not switch DSLs or bump AGP solely on the basis of the missing file without inspecting the resolved variant and the complete R8 logs.
- Google's [Baseline Profile build-type guide](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles) confirms the plugin-generated `benchmarkRelease` is intended to be minified and profileable, while `nonMinifiedRelease` is intended for profile capture. Preserve this distinction.

### No speculative edits

No mapping-file path was broadened, no fallback file accepted, no R8 assertion weakened, no keep rules or Sentry configuration changed, and no AGP/Gradle/AndroidX version changed in response to this run. The next code change must follow direct evidence from the uploaded Gradle diagnostics and exact AGP/AndroidX behavior. Do not start another CI run until that investigation and a deterministic local/CI-level regression check are complete.


### Diagnostics artifact inspection — confirmed R8 did not execute

The complete diagnostics ZIP for run #14 was downloaded and inspected, not inferred from the short job summary. Its SHA-256 is `f5dad1a47e0bfa10425c4a07d76b1f90f5c83993067c38a65470b6685ebb6dc9`.

Both complete Gradle phase logs show the same concrete pattern:
- Full `benchmarkRelease`: `mergeDexFullBenchmarkRelease` → `l8DexDesugarLibFullBenchmarkRelease` → `compileFullBenchmarkReleaseArtProfile` → `packageFullBenchmarkRelease` → `assembleFullBenchmarkRelease`.
- Play Store `benchmarkRelease`: `mergeDexPlaystoreBenchmarkRelease` → `l8DexDesugarLibPlaystoreBenchmarkRelease` → `compilePlaystoreBenchmarkReleaseArtProfile` → `packagePlaystoreBenchmarkRelease` → `assemblePlaystoreBenchmarkRelease`.

Neither log contains an R8 minification task, mapping output, or R8 Configuration Analyzer report. The diagnostics status file explicitly says both expected `configanalyzer.html` reports were not generated. Both Gradle invocations succeeded, but they assembled non-R8 benchmark variants. Therefore the bundle validator correctly rejected them; the missing mapping is not merely a misplaced `mapping.txt` path. **Do not package these APKs as the R8 benchmark arm.**

This narrows the issue: the actual `benchmarkRelease` variants were not passed through R8 in the successful Gradle task groups, despite the Baseline Profile plugin's documented intended configuration. The cause must be resolved in the effective build-type/AGP/plugin configuration before another bundle build.

### Version compatibility lead, now higher priority

Google's current [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) and [issue 556343682](https://issuetracker.google.com/issues/556343682) record that the new `optimization { enable = true }` DSL did not propagate `ApplicationVariant.isMinifyEnabled` in AGP 9.3.0. Google marks this fixed in AGP 9.3.3 and AGP 9.4.1; Nuvio pins AGP 9.4.0. This issue is now a high-priority compatibility check because Baseline Profile plugin variant setup and related Gradle plugins may inspect the public variant minification property. It is still not proven to be the exact cause here: the app's current `release` block uses the legacy DSL, and the full AGP/AndroidX interaction needs source-level verification against the exact resolved versions.

Do not jump directly to AGP 9.4.1, change DSLs, or manually set minification on the custom type without checking the exact AndroidX Baseline Profile plugin 1.5.0 implementation and the effective AGP 9.4.0 variant model. Any fix must result in observable `minify<Variant>WithR8` execution, non-empty mapping files for both flavors, generated R8 Configuration Analyzer reports, and a compiled Baseline Profile in both benchmark APKs. Keep the existing artifact checks as regression protection.

No app runtime code, Macrobenchmark metric, iteration count, compilation mode, profile source, keep rule, signing behavior or phone-runner command was changed.


### Root cause confirmed — manually pre-created benchmark build type bypassed R8 defaults

The exact AndroidX Baseline Profile Gradle plugin **1.5.0** source was inspected at the 1.5.0 release merge commit [852ea00](https://android.googlesource.com/platform/frameworks/support/+/852ea00bf5d9b66c17a07d52320d7ea561b5d6fa/benchmark/baseline-profile-gradle-plugin/src/main/kotlin/androidx/baselineprofile/gradle/apptarget/BaselineProfileAppTargetPlugin.kt).

For AGP 8.1+ the plugin creates the benchmark build type by copying the original build type. Its source explicitly distinguishes:
- **New generated build type:** copies `base.isMinifyEnabled` and `base.isShrinkResources` to the extended type.
- **Already-existing custom build type:** the plugin's `overrideConfigureBlock` sets JNI/debuggable/profileable and test-coverage properties, but deliberately does **not** set `isMinifyEnabled` or `isShrinkResources`. This preserves user customization.

Nuvio had manually created both `benchmarkRelease` and `nonMinifiedRelease` in `androidApp/build.gradle.kts` without `initWith(getByName("release"))`. Because the existing `benchmarkRelease` did not inherit the release configuration and the plugin intentionally left its minification settings alone, R8 never ran. This exactly matches run #14's Gradle logs: neither benchmark flavor executed an R8 task, and neither generated `mapping.txt` or `configanalyzer.html`.

This is the confirmed cause. The AGP 9.4.0 / issue 556343682 compatibility lead above is not needed to explain this failure and is not being changed as part of this fix.

### Concrete fix committed

On `main-build-testing`:
- Commit `2730d3f7d576b8fa74c12606f7ce8280c2104ff2` updates `androidApp/build.gradle.kts`.
- Both custom build types now call `initWith(getByName("release"))` before applying the benchmark package suffix and debug signing.
- `benchmarkRelease` explicitly sets `isMinifyEnabled = true` and `isShrinkResources = true`, so the benchmark arm remains R8-optimized even if a developer intentionally disables minification for ordinary release builds.
- `nonMinifiedRelease` inherits the release dependencies, ProGuard files and common configuration first; the Baseline Profile plugin's documented override then disables minification/shrinking for readable profile capture.

Regression guard:
- Commit `d0849733fdb1f83c543e933244806394d7bb84cb` updates `scripts/tests/test-android-benchmark-bundle-assembly.sh`.
- The test now requires both custom build types to inherit `release`, and requires the benchmark type to explicitly enable code and resource shrinking. This prevents another successful-but-unminified benchmark build from passing the preflight contract.

This is a build-configuration correction only. No app runtime code, R8 keep rule, Baseline Profile contents, Startup Profile journey, Macrobenchmark metrics, iterations, compilation modes or LADB commands changed.

### Verification required before calling the bundle ready

The prior run #14 remains failed and its APKs must not be used as the benchmark bundle. The new configuration and regression guard must be checked by a single fresh-source run. That run must show:
1. the pre-build regression tests pass;
2. actual R8 minification tasks execute for both `fullBenchmarkRelease` and `playstoreBenchmarkRelease`;
3. non-empty mapping files and both R8 Configuration Analyzer reports exist;
4. both benchmark APKs contain compiled Baseline Profiles;
5. all eight APKs pass package ID, debuggable, profileable and arm64-only checks;
6. the 12-file bundle checksum validates and the artifact uploads.

The previous CI branch is now stale relative to the updated `main-build-testing` commits. Do not reuse it. The next authorized CI branch must be created directly from the current `main-build-testing` HEAD, must pass the freshness guard, and must run only once after checking that no prior run remains active.


### Fresh-run preflight follow-up — the stale checkpoint assertion was also still on main

The fresh R8-fix run [44105881853](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44105881853), commit `4e08c3cbbc2f69deaf84a0bf8102841e1bb4b9bb`, passed the source-freshness guard and the new build-type inheritance assertions, then failed before Gradle for the same obsolete checkpoint-step assertion previously seen in run #13. The CI-only branch had the corrected string, but the canonical test on `main-build-testing` still contained `Upload full-distribution build checkpoint`; the workflow step is `Upload full profile-capture checkpoint`.

Corrected on `main-build-testing` in commit `cbbd32f72dddfc3066b2782f13c2f64fb3561446`. This was a missed propagation of the earlier test correction, not a new app or R8 issue. Do not repeat the previous mistake: create the next isolated CI branch from the latest `main-build-testing` HEAD and verify the test file itself contains no reference to the obsolete step name before pushing. The run did not execute Gradle or produce APKs.


## 18 November 2026 — on-device Baseline Profile readiness failure

### User-provided LADB evidence

Two screenshots from the Galaxy phone show the Baseline Profile instrumentation test failing in both the Full and Play Store profile-capture flows. The reported assertion is:

`HomeScreen appeared, but Nuvio did not expose its fully-drawn marker 'nuvio_startup_ready' before the 30-second startup deadline.`

The diagnostic snapshot printed by the test reports:
- Last package to report an accessibility event: `com.draco.ladb`
- Expected app package: `com.nuvio.media.benchmark`
- AppGate main/profile-loading markers: false
- Main-content loading/ready and launch-overlay markers: false
- All home-ready/profile-switch diagnostic markers: false
- HomeScreen marker and fully-drawn marker: false

Important interpretation: the test had observed the HomeScreen marker at an earlier point (otherwise it would have thrown the separate “did not expose the HomeScreen marker” assertion), but it did not observe `nuvio_startup_ready` within the shared 30-second deadline. By the time diagnostics were sampled, neither marker was visible.

### What the code proves

- `BaselineProfileGenerator.awaitMainContentReady()` waits first for `nuvio_benchmark_home`, then waits for `nuvio_startup_ready` using the remaining time from one 30-second deadline.
- `MainActivity` exposes `nuvio_startup_ready` only while its `appReady` state is true; `ReportDrawnWhen { appReady.value }` uses that same state for TTFD.
- The callback is supplied by AppGate. MainAppContent reports root readiness only after `initialHomeReady && !profileSwitchLoading` and after the launch overlay has fully exited.
- Therefore this is not evidence that the APK failed to install, that the Home test tag is globally unrecognised, or that a longer timeout is the fix. The measured failure is that the app's fully-drawn readiness predicate did not become observable after Home was seen.
- `UiDevice.getCurrentPackageName()` is explicitly documented as the **last package to report accessibility events**, not the foreground package. The screenshot's `com.draco.ladb` value cannot prove that LADB displaced Nuvio or that Nuvio was foreground at timeout.

### Root cause status: not yet proven

The screenshots do not include the before/after resumed-activity snapshots or the full instrumentation log. The final false marker snapshot cannot distinguish:
1. Nuvio leaving the foreground or its activity/process being stopped after the initial Home observation;
2. Nuvio remaining visible while AppGate/MainAppContent readiness stayed false (for example, initial-home or launch-overlay state not settling);
3. an activity/window transition that made the Compose markers unavailable to UI Automator.

Do not change the 30-second timeout, remove the Home/fully-drawn waits, weaken `ReportDrawnWhen`, use a sleep, skip profile collection, or accept TTID-only output. Those changes would mask the missing readiness signal and weaken the benchmark contract.

### Required evidence before a code fix

For one failed Full run, preserve and inspect the run directory's:
- `instrumentation.log`
- `foreground-before.txt`
- `foreground-after.txt`

The phone runner already captures the foreground snapshots around instrumentation. Compare the resumed activity/package before and after the test and inspect the complete first assertion/stack trace. If those files are absent, collect a single failed run with the updated runner that retains them; do not repeat both flavors until the failure layer is known. The app-side readiness markers and instrumentation trace must be correlated before choosing a fix.

### Separate CI preflight issue (17 November)

The new fresh-source CI branch passed its stale-source guard (`main-build-testing` was its exact merge base; `behind_by = 0`) but run [#13](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/44018086908) failed before any Gradle build. The phone-runner shell test passed; the bundle-assembly test failed because its required-step list still expected `Upload full-distribution build checkpoint`, while the current workflow step and its own later assertion use `Upload full profile-capture checkpoint`. The `INSTALL_FAILED_UPDATE_INCOMPATIBLE` text in the log is a deliberately simulated response in the phone-runner test; that test printed PASS and it was not the failing assertion.

This stale assertion is a concrete test/workflow contract mismatch, but it has not been edited in this checkpoint. The fresh CI branch is push-triggered, and the user explicitly said not to run builds/CI without authorization; do not push a fix to it or dispatch another run until explicitly authorized. The canonical `main-build-testing` workflow remains manual-only.

### Research cross-check

Official Android guidance confirms:
- Macrobenchmark runs in a separate process and uses UI Automator to inspect target-app UI; Compose test tags must be exposed with `testTagsAsResourceId = true`.
- `startActivityAndWait()` waits for the target activity to render its first frame; it does not mean asynchronous app content is fully ready.
- `ReportDrawnWhen`/TTFD should be tied to the app's actual user-ready condition, and the benchmark should separately wait for the relevant content.
- `UiDevice.getCurrentPackageName()` returns the last accessibility-event package, not the foreground package.

Sources:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-control-app
- https://developer.android.com/codelabs/android-macrobenchmark-inspect
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- https://developer.android.com/reference/kotlin/androidx/test/uiautomator/UiDevice
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview

No app, benchmark, timeout, profile, or phone-runner code was changed for this incident because the supplied evidence does not yet prove which component prevented the readiness predicate from becoming true.


## 18 November 2026 — UIAutomator foreground recovery and readiness-test contract

### Confirmed API semantics and dependency

The current version catalog pins `androidx.test.uiautomator:uiautomator` to **2.4.1**. The Baseline Profile test module consumes that alias.

The official Android API reference and AndroidX source distinguish:
- `UiDevice.getCurrentPackageName()` — the last package to report accessibility events. It is not a foreground-window query.
- `UiDevice.findWindow(ByWindowSelector)` — returns the matching top-level window or null.
- `By.Window.active(true)` — selects the active window.
- `UiWindow.getPackageName()` — returns the package owning that window.
- `UiWindow.isActive()` — the window the user is currently interacting with.
- `UiWindow.isFocused()` — the window holding input focus.

A null active window is unknown; it must not trigger recovery based on stale event metadata.

References:
- [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice)
- [ByWindowSelector API](https://developer.android.com/reference/androidx/test/uiautomator/ByWindowSelector)
- [UiWindow API](https://developer.android.com/reference/androidx/test/uiautomator/UiWindow)
- [AndroidX Test source](https://github.com/android/android-test)

### Concrete source correction

`StartupReadiness` now reports the active-window package independently from the last accessibility-event package, and uses the active-window package (not `getCurrentPackageName()`) to decide whether a known LADB/Termux/terminal/shell package should be returned to Nuvio. An absent or unreadable active window is treated as unknown. The known-shell package predicate is shared by both polling paths.

This changes only benchmark harness foreground-recovery and diagnostics. It does not change the measured app, readiness signal, timeout, TTFD contract, metrics, profile generation, profile rules or production UI.

### Concrete regression-test correction

The readiness script previously applied shared-helper checks to both `BaselineProfileGenerator.java` and `StartupBenchmark.java`, although the implementation now lives in `StartupReadiness.java` and both test classes delegate to it. The script now checks caller-owned marker wait paths in the two test classes and checks shared diagnostics/helper behavior in `StartupReadiness.java`.

### Verification boundary

The branch and files were fetched immediately before editing at head `6c17ef5f7e90dcccc2cf501d243e84b6904b6326`. The API signatures were checked against Android documentation for the dependency pinned in the repository. No Gradle build, device run, shell regression, GitHub Actions workflow or CI was started. The source and shell edits have not been executed in this session. A phone run remains necessary to validate Samsung/LADB active-window reporting and to establish whether app-side readiness now completes. Do not claim the readiness failure is fixed without that evidence.


## 18 November 2026 — follow-up review of active-window recovery and readiness chain

### Follow-up review of the previous commit set

The three-commit comparison against the pre-change branch was clean (three commits ahead, zero behind). Reviewing the committed test script found that the first correction declared `READINESS_SOURCE` but iterated only `GENERATOR_SOURCE` and `BENCHMARK_SOURCE`. Consequently, the helper-specific assertions inside that loop were unreachable. This was a test implementation defect, not an Android runtime defect.

The regression script has now been corrected again:
- the two benchmark classes are checked for delegation to `StartupReadiness.awaitHomeAndFullyDrawn(device)`;
- the shared helper is checked directly for package-scoped marker lookup, active-window lookup, correctly labelled diagnostic output, central shell-package matching and a shared elapsed-realtime budget;
- the test does not require shared implementation details to be duplicated into the two caller classes.

This is a source-level contract correction. The script has not been run in this session.

### UIAutomator 2.4.1 research refinement

The repository pins UIAutomator **2.4.1**. Android's release notes date this stable release to **4 November 2026**. The window selector API originated in the 2.4.0 line, and 2.4.1 exposes:
- `UiDevice.findWindow(ByWindowSelector)`, returning the first matching top-level window or null;
- `By.Window.active(true)` and `By.Window.focused(true)`;
- `UiWindow.getPackageName()`, `isActive()`, and `isFocused()`.

Android's API reference describes `isActive()` as the window the user is currently interacting with and `isFocused()` as the window with input focus. This supports using the active window for the recovery decision. It does not prove Samsung firmware always exposes the desired active accessibility window under every LADB/overlay condition; that remains a device validation item.

Sources:
- [UIAutomator release notes](https://developer.android.com/jetpack/androidx/releases/test-uiautomator)
- [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice)
- [ByWindowSelector API](https://developer.android.com/reference/androidx/test/uiautomator/ByWindowSelector)
- [UiWindow API](https://developer.android.com/reference/androidx/test/uiautomator/UiWindow)

### App readiness chain — findings and non-findings

Current code separates these milestones:
1. Home route marker mounted in `HomeScreen`;
2. `MainAppContent` initial-home readiness condition;
3. launch overlay fully exited;
4. AppGate/root readiness callback;
5. `MainActivity` `appReady` state;
6. Compose `ReportDrawnWhen { appReady.value }` fully-drawn report.

The Home marker is intentionally earlier than full readiness. Android's Macrobenchmark documentation confirms TTID is first frame and TTFD is determined by the app's fully-drawn report plus the following rendered frame. Retaining both signals is necessary; do not replace the TTFD callback with a Home marker or a fixed sleep.

`rememberSaveable` can restore UI state when Android recreates an Activity/process with saved instance state. Android documents that it does not retain state after complete user dismissal (for example, swiping the app away from Recents). This means the remembered `gateScreen` state is a valid lifecycle-recreation consideration, but current evidence does not make it the cause of the reported LADB force-stop/cold-start timeout. Do not reset gate state or change its saveability based on speculation.

The source path still lacks stage-timestamp evidence for a failed physical run. The next decisive record should correlate:
- active window package and focused window package during marker wait;
- target package hierarchy presence;
- the app-side AppGate/MainAppContent readiness markers over time;
- foreground/resumed-activity snapshots before and after instrumentation;
- the first failure stack trace and exact APK provenance.

### Official startup performance guidance

Android recommends profiling cold startup with Macrobenchmark and system traces, separating TTID from TTFD, then investigating operations occupying long spans, main-thread blocking, unnecessary initialization, library loading, composition, layout and Binder calls. It recommends deferring nonessential startup work only after confirming from traces that it is nonessential.

No startup optimizations were applied in this follow-up. This project needs a captured trace and readiness-stage timings before selecting work to move or remove.

Sources:
- [App startup analysis and optimization](https://developer.android.com/topic/performance/appstartup/analysis-optimization)
- [Startup timing metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [Inspect app performance with Macrobenchmark](https://developer.android.com/codelabs/android-macrobenchmark-inspect)
- [Save UI state in Compose](https://developer.android.com/develop/ui/compose/state-saving)

### Verification boundary

The readiness test correction is committed to `main-build-testing`. No shell test, Gradle command, build, instrumentation test, phone benchmark or CI workflow was run. Remote source verification is not a substitute for executing the regression suite or validating on the Galaxy device. The app-side readiness failure remains open.
