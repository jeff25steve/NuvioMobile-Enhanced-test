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



## 18 November 2026 — focused-window diagnostics and final source review

### Active versus focused window: confirmed distinction

A second independent review was made against the AndroidX UI Automator 2.4.1 API reference and the AndroidX source change that introduced multi-window support.

- `By.Window.active(true)` matches the window marked active, meaning the window the user is currently interacting with.
- `By.Window.focused(true)` matches the window holding input focus.
- `UiWindow.isActive()` and `UiWindow.isFocused()` expose the corresponding states.
- `UiDevice.findWindow(selector)` returns the first top-level match or null. `findWindows(selector)` returns all matching windows in descending layer Z-order (topmost first).

These are distinct signals. Input focus can belong to a dialog or another input surface and must not be treated as a general foreground/recovery signal. The recovery decision should continue to use the active window. A focused-window package is useful as additional diagnostic context only.

The UI Automator 2.4.1 release is dated 4 November 2026. Its release notes specifically mention a ShellServer random-port collision fix; that release note is not evidence of any fix to LADB foreground reporting.

References:
- [UI Automator 2.4.1 release notes](https://developer.android.com/jetpack/androidx/releases/test-uiautomator)
- [ByWindowSelector API](https://developer.android.com/reference/androidx/test/uiautomator/ByWindowSelector)
- [UiWindow API](https://developer.android.com/reference/androidx/test/uiautomator/UiWindow)
- [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice)
- [AndroidX multi-window API source change](https://android.googlesource.com/platform/frameworks/support/+/fb587b0d9d41b42ba86ad6a7fc441819275edabd)

### App readiness / TTFD cross-check

The current `MainActivity` calls `ReportDrawnWhen { appReady.value }` and exposes the `nuvio_startup_ready` accessibility tag from that same state. The state is set through the `App(onAppReady = ...)` callback. This matches Android's Compose guidance: `ReportDrawnWhen` adds a predicate to the conditions before `Activity.reportFullyDrawn` is called.

The AndroidX Compose test suite verifies that `ReportDrawnWhen` reevaluates after recomposition, reports after its predicate becomes true, and does not report a second time after the initial report. This supports keeping the callback tied to the app-ready state; it does not support bypassing the readiness condition to make the benchmark pass.

Android's Macrobenchmark definition also matters for interpreting results:
- TTID ends at the first frame of the destination screen.
- TTFD ends when the app reports fully drawn and the frame containing/after that signal is rendered.
- A first-frame launch wait is not a substitute for waiting on asynchronously loaded content.

References:
- [ReportDrawnWhen API](https://developer.android.com/reference/kotlin/androidx/activity/compose/ReportDrawnWhen.composable)
- [AndroidX ReportDrawn tests](https://android.googlesource.com/platform/frameworks/support/+/cb7b46e014cacd6427ee9ed0a1f422b74cd4c4a3/activity/activity-compose/src/androidTest/java/androidx/activity/compose/ReportDrawnTest.kt)
- [Macrobenchmark startup metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [App startup time](https://developer.android.com/topic/performance/issues/launch-time)

### Phone-runner evidence gap

The existing phone runner captures foreground state before and after the entire instrumentation invocation. It preserves the instrumentation log and those snapshots in the run directory. It does not record the active/focused window or marker-stage state at each readiness polling interval. Therefore a before/after snapshot alone cannot establish whether LADB briefly owned the active window during the failed wait, or whether Nuvio remained active while its own ready predicate stayed false.

The most informative next diagnostic run should capture a compact, timestamped stage trace while the wait is in progress: elapsed time, active-window package, focused-window package, target-package hierarchy presence, and each app-side readiness marker. It should retain the existing before/after resumed-activity snapshots and first failure. Keep this diagnostic capture bounded and opt-in to avoid adding repeated shell I/O to normal benchmark iterations.

This is a recommended diagnostics-only follow-up, not a conclusion that LADB caused the timeout. No Samsung/LADB behavior has been proven without a phone capture.

### Source review conclusion

No application startup optimization is justified by the evidence currently available. The readiness condition is wired consistently from the app callback to both the accessibility marker and Compose's fully-drawn report. The unresolved issue is missing runtime evidence about which stage fails on the physical phone. The benchmark harness's current active-window recovery and diagnostic labels are consistent with the documented API semantics, but device validation remains outstanding.

No build, shell test, instrumentation run, phone benchmark or CI workflow was executed during this review. The app-side readiness failure remains open.



## 18 November 2026 — Grok clip review and UI Automator logging correction

### Evidence extracted from the user-provided LADB recording

The clip shows a Play Store Baseline Profile collection attempt failing after approximately 32.8 seconds. The visible output includes:
- HomeScreen marker visible: true.
- AppGate main marker visible: true.
- Main content ready marker visible: true.
- Initial-home readiness/profile-switch diagnostic: home-ready and profile-switch idle.
- Fully-drawn marker visible: false.
- The last accessibility-event package shown is the benchmark app package, not LADB, at the time of the final diagnostic snapshot.

This is materially different from the earlier screenshot snapshot. It shows that the target app and Home content were present; the outstanding app signal was the fully-drawn marker. It does not prove LADB displaced Nuvio.

The recording also shows a supplemental hierarchy-dump failure: the log says the XML hierarchy dumper can throw on invalid characters in accessibility text, and that diagnostic was omitted. This is a separate diagnostic serialization path; the clip's horizontally clipped stack does not let us identify a precise malformed string or prove that it caused the missing fully-drawn signal.

### Confirmed code-level logging defect

The shared `StartupReadiness.markerVisible()` implementation previously caught every `Exception` thrown by `UiDevice.findObject()` and returned `false`. That collapsed two distinct outcomes:
1. selector completed normally and the marker was absent;
2. UI Automator/accessibility traversal threw and the marker query did not complete.

The second outcome could therefore be misreported as a normal marker timeout. The shared helper has been changed to let marker-query exceptions propagate and to wrap them at the Home or fully-drawn stage with the original cause attached. A marker-query failure is now identified as an accessibility query failure, not as "marker not visible".

Foreground recovery command failures are also no longer silently ignored when the active window is a known shell/terminal; they are surfaced with the affected marker and active package context. The active-window decision, shared 60-second overall deadline, and separate Home and TTFD marker contract are unchanged.

### External-source cross-check

The official AndroidX UI Automator API documents `UiDevice.findObject(BySelector)` as a selector search that returns the matching object or null when no match exists. The AndroidX implementation delegates the search to `ByMatcher`, which traverses the accessibility-node hierarchy. This is not the XML dumper path.

The AndroidX XML dumper separately serializes accessibility node properties (including text and content descriptions) into XML attributes and contains character-sanitization logic. The recording's omitted hierarchy dump is consistent with a diagnostic serialization problem; it should not be conflated with the actual selector lookup outcome.

An independent real-world issue report also documents `ByMatcher` failing inside recursive `AccessibilityNodeInfo.getChild()` traversal when the accessibility framework returns broken node data. That report is not proof of the same defect on this Galaxy device, but it supports not suppressing traversal exceptions.

References:
- [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice)
- [AndroidX AccessibilityNodeInfoDumper source](https://android.googlesource.com/platform/frameworks/support/+/48d9273f1facf771ff812a10d8bb46f6f8773ed4/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/AccessibilityNodeInfoDumper.java)
- [AndroidX ByMatcher source change](https://github.com/friendboy1/androidx/commit/3f4b3cd915328e0539c135e3e12e1773a03a0689)
- [Independent UI Automator accessibility-node traversal report](https://github.com/openatx/uiautomator2/issues/770)

### Readiness chain follow-up

The current Android app uses the normal AppGate path with `renderMainContent = true`. The normal path forwards `MainAppContent.onRootContentReady` to `MainActivity.appReady`. AppGate's alternate `!renderMainContent` path uses separate external readiness and overlay checks and is not the active Android startup path.

In MainAppContent, root readiness is `initialHomeReady && !profileSwitchLoading`; the callback also waits for the launch overlay exit to be idle and not visible. The clip's Home-ready/profile-switch-idle diagnostic indicates those inputs were satisfied at the final snapshot, while the fully-drawn marker was still false. That narrows the remaining app-side question to whether the normal AppGate callback was invoked and propagated on this run, or whether the diagnostic snapshot and the marker query observed different states/times. It does not justify weakening TTFD or skipping the app-ready contract.

### Remaining decisive evidence

The next on-device capture should preserve:
- the first complete exception and stack trace from the original instrumentation failure;
- active and focused window packages during the wait;
- marker query stage and original exception type/message, if one occurs;
- app-side Home-ready, profile-switch-idle, overlay-idle, AppGate main, and appReady values at the same timestamps;
- foreground/resumed activity before and after instrumentation;
- exact APK flavor, package version and build/source commit.

The current recording does not show enough of the first exception line or stack to identify a specific invalid character or prove which AndroidX call threw. Do not attribute the failure to LADB or XML text alone.

### Verification boundary

The logging correction and its source-level regression assertions were committed remotely. No test script, build, instrumentation run, phone benchmark, or CI workflow was run during this work. Therefore the changes are source-reviewed but unexecuted. The physical-device readiness problem remains unresolved until a fresh run captures the complete original exception and the updated stage evidence.



## 18 November 2026 — full MP4 re-review: readiness state, revision mismatch and shell E2BIG

### Full recording review and corrections to the earlier summary

The uploaded MP4 is 16.13 seconds at 1080x2340, 120 fps. I reviewed the contact sheet and individual frames from the start, failure, middle and final result.

The clip is a **Play Store Baseline Profile collection** attempt. Its visible instrumentation output reports a 30-second readiness deadline, not the 60-second value in the currently inspected shared helper. The test reports:
- `HomeScreen marker visible now: true`;
- `AppGate main marker visible now: true`;
- `Main content ready marker visible now: true`;
- `AppGate profile-loading marker visible now: false`;
- `AppGate current gate marker: not visible`;
- `Main content loading marker visible now: false`;
- `Launch overlay transition marker visible now: false`;
- `Readiness home-ready/profile-idle: false`;
- `Readiness home-ready/profile-loading: false`;
- `Readiness home-pending/profile-idle: false`;
- `Readiness home-pending/profile-loading: false`;
- `HomeScreen marker visible now: true`;
- `Fully-drawn marker visible now: false`.

The four false readiness tags mean the dedicated 1dp diagnostic node was not observed in the final accessibility snapshot. They do **not** prove the home-ready/profile-idle combination was true. The separate main-content-ready marker was visible at the same snapshot, so the marker set is internally inconsistent or the different node queries sampled different tree states; the exact reason cannot be determined from a single final snapshot.

The last accessibility-event package is `com.nuvio.media.benchmark`. This is not a foreground-window assertion, but it is also not positive evidence that LADB owned the last accessibility event. The video does not establish that LADB stole foreground.

### Critical source/APK revision mismatch

The visible failure stack references:
- `BaselineProfileGenerator.awaitMainContentReady(...:102)`;
- `BaselineProfileGenerator.lambda$startup$1(...:123)`;
- `BaselineProfileGenerator.startup(...:112)`.

The currently fetched `BaselineProfileGenerator.java` on `main-build-testing` contains no `awaitMainContentReady()` method; it delegates to `StartupReadiness.awaitHomeAndFullyDrawn(device)`. That file is identical at the previous 6c17ef5 checkpoint and current a918aa3/bb78b9b follow-up history.

Therefore, the APK used for this video was built from an **older or otherwise different source revision** than the current remote helper implementation. The recording cannot be used as a runtime verification of the 60-second shared helper or of the recent logging correction. The 30-second message and old stack line numbers are consistent with that mismatch. APK provenance must be established from the installed APK/build artifact (version/commit or checksum) before interpreting the next run as testing the latest source.

### Hierarchy-dump exception is secondary diagnostic output

The video explicitly states that Android's XML hierarchy dumper can throw on invalid characters in accessibility text and that the hierarchy was omitted so it would not mask the original benchmark failure.

AndroidX UI Automator has a separate `AccessibilityNodeInfoDumper` implementation which traverses visible accessibility nodes and serializes text, resource IDs, class names, package names and content descriptions as XML attributes. Its character sanitization is not a guarantee that all malformed character sequences or serializer edge cases are safe. This failure means the optional hierarchy snapshot was unavailable. It does not demonstrate that `findObject(BySelector)` failed, and it is not the primary failure shown by the test assertion.

References:
- [AndroidX AccessibilityNodeInfoDumper source](https://android.googlesource.com/platform/frameworks/support/+/48d9273f1facf771ff812a10d8bb46f6f8773ed4/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/AccessibilityNodeInfoDumper.java)
- [UiDevice API](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice)

### Separate Android shell error: printf argument-size failure

The recording also visibly reports `/system/bin/printf: Argument list too long` and associates it with the phone runner script. This is consistent with an `execve()` `E2BIG` failure when an external utility is invoked with an oversized argument. The kernel limits each individual argument as well as the combined argument/environment size; passing a large multiline diagnostic as one argument is unsafe. Reading data via stdin or a file avoids that specific argument-vector limit.

However, I inspected the current runner at the reported line and its `run_instrumentation()` function:
- `am instrument -w -r` redirects output directly to `instrumentation.log`;
- the completed log is displayed using `cat "$RUN_DIR/instrumentation.log"`;
- there is no intentional `printf "$instrumentation_output"` call.

The reported line number `nuvio-phone-benchmark.sh[948]` also does not map to a current `printf` call in the remote script (line 948 is the end of the case statement). This points to a runner-revision mismatch, generated/expanded command, or a different shell execution path. It would be unsafe to patch an unrelated `printf` site without the exact script copy that ran on the phone.

Current runner evidence:
- `run_instrumentation()` writes `am instrument` stdout/stderr to a file, then cats the file;
- `resolve_instrumentation()` uses a short `pm list instrumentation` line inside command substitution;
- foreground diagnostics use `dumpsys` output captured in a shell variable, then pass it to builtins/filters;
- install output is captured in shell variables and later printed.
The first item avoids carrying the full instrumentation stream as a shell argument. The other command substitutions are bounded by device output but should still avoid sending arbitrarily large captured values as one external command argument.

Android kernel `execve` enforces `MAX_ARG_STRLEN` and combined argv/env limits. The error is therefore a genuine logging/runner concern, but the current file alone does not identify the exact oversized argument in this old run.

References:
- [Linux execve(2) argument/environment error limits](https://man7.org/linux/man-pages/man2/execve.2.html)
- [AOSP Toybox shell execution source](https://android.googlesource.com/platform/external/toybox/+/android-6.0.1_r3/toys/pending/sh.c)
- [Android Bionic exec implementation](https://android.googlesource.com/platform/bionic/+/HEAD/libc/bionic/exec.cpp)

### What the current app source says (separate from this old APK)

On the currently fetched source, the standard Android path has `renderMainContent = true`. The normal `AppGate` branch forwards `MainAppContent.onRootContentReady` to `MainActivity.appReady`. MainAppContent calculates:
`rootContentReady = !ownsAppRuntime || (initialHomeReady && !profileSwitchLoading)`
and only reports true after the launch-overlay transition is idle and not visible.

This is a valid area to instrument on a fresh APK, but the video cannot tell whether the current code has the same state-flow issue because its stack points to the older generator path and its tag set was sampled at one final moment.

### Required next capture and decision gates

Before attributing the next failure or claiming the logging is fixed, capture:
1. exact test APK and app APK hashes/build variant/build commit used by the phone;
2. exact `nuvio-phone-benchmark.sh` copied into the staging directory, including a checksum and its own line 948;
3. the untruncated `instrumentation.log` from the run folder;
4. the exact `printf` command and argument/source which emitted `Argument list too long` (or a trace of shell command execution around that invocation);
5. app readiness values from the same timestamp as `appReady` and the marker query;
6. active/focused window package and resumed activity before/after plus a bounded stage timeline during the wait.

Keep the hierarchy dump best-effort and ensure a dump failure never replaces the original assertion. Keep the original exception distinct from a normal marker timeout. Do not weaken the TTFD criterion or extend the timeout based only on this capture.

### Verification boundary

This is a source/recording review only. No build, shell test, instrumentation test, new phone run or CI was executed. The current branch code does not yet have the full timestamped stage telemetry or exact runner `E2BIG` root-cause evidence. The benchmark failure remains open; the recording does not verify the most recent source fix.


## 18 November 2026 — prioritized diagnostics implementation (LADB user-run correction)

The recording is the user's own LADB run, not a Grok-created or Grok-supplied recording. Any prior label implying otherwise is incorrect.

### Prioritized source changes

1. **Compose marker selector corrected.** All relevant markers originate from `Modifier.testTag` with root `testTagsAsResourceId = true`. Android's Compose interoperability guidance says this maps tags to the bare resource name and specifically cautions against `By.res(resourcePackage, resourceId)` for Compose tags. The shared helper now uses `By.res(marker)` and separately checks that the benchmark app package exists in the accessibility hierarchy.

2. **Root readiness state moved onto visible semantics.** The former 1dp child exposed a tag-only diagnostic node. Compose's official accessibility implementation notes say testTag-only nodes are kept for UI Automator but marked unimportant for screen readers; they are more vulnerable to tree changes/visibility pruning than a visible semantics-bearing root. The main content root now includes a proper `stateDescription` with `home_ready|pending_profile_switch_idle|loading` state, while keeping the existing primary test tag unchanged. This avoids adding fake content descriptions or extra accessible UI elements.

3. **Runner provenance recorded per invocation.** A new `provenance.txt` records runner path and SHA-256, staged runner SHA-256, flavor/action, APK paths/hashes and resolved instrumentation component. This should make a stale script or APK immediately apparent on the next LADB run.

4. **Wireless debugging check no longer buffers large dumpsys text in a shell variable.** It captures the two dumpsys outputs to a temporary file, greps that file, and removes it when clear. This avoids passing the full combined value to an external filter/printf pipeline. The runner already writes `am instrument` directly to `instrumentation.log`, so no change was needed there.

### Context7 / Exa verification

- Context7 Compose docs: `Modifier.semantics` creates a semantics node; `testTag` is exposed through `testTagsAsResourceId` as a resource-name handle; `stateDescription` is an established accessibility semantics property.
- Official Compose interoperability guide states to use `By.res("tag")` for Compose testTags and warns against the two-argument package/resource form.
- AndroidX Compose implementation notes state that testTag-only nodes are marked unimportant for accessibility, and describe automatic pruning of invisible/occluded semantics.
- AOSP Toybox marks `printf` as MAYFORK, so it can be executed as a separate process by the shell and subject to exec argument limits. This supports the general E2BIG concern, but does not identify the exact oversized argument from the prior run.

References:
- [Compose UI Automator interoperability](https://developer.android.com/develop/ui/compose/testing/interoperability)
- [Compose semantics API](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/package-summary)
- [AndroidX accessibility implementation notes](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/accessibility/android_a11y_implementation_notes.md)
- [AOSP Toybox generated command metadata](https://android.googlesource.com/platform/external/toybox/+/refs/tags/android-13.0.0_r28/android/device/generated/newtoys.h)
- [AOSP Toybox printf MAYFORK change](https://landley.net/toybox/git/commit/7348ee6049cb)

### Remaining uncertainty

- The prior LADB APK's stack still points to an older generator implementation; this new source change must be built into a new benchmark APK before it can be tested.
- The stateDescription is now on the already-visible main root, but its AccessibilityNodeInfo output still needs confirmation on the actual Samsung device.
- The `printf: Argument list too long` line number in the video does not match the current runner. The new per-run checksum will identify the exact script used next time. The file-based wireless-debug check removes one large command-substitution path, but the exact old E2BIG origin remains unproven.
- The root readiness stateDescription is diagnostic only. It does not replace the separate fully-drawn marker or change the TTFD requirement.

No test script, build, instrumentation, LADB run or CI was executed. These changes are source-reviewed only.


## 18 November 2026 — upstream comparison and diagnostic API correction

### Official Nuvio comparison

Reviewed the official [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) repository on its current `cmp-rewrite` branch. Its README identifies the Android build as `:androidApp:assembleFullDebug` and confirms the shared Kotlin Multiplatform / Compose Multiplatform architecture.

The official version catalog currently pins:
- `androidx-benchmark = 1.5.0`
- `androidx-uiautomator = 2.3.0`
- `androidx-testRunner = 1.7.0`

The enhanced benchmark branch pins UI Automator `2.4.1` (and has its own dedicated baseline-profile / macrobenchmark harness). Therefore upstream is not ahead of the enhanced branch on the UI Automator version. Do not downgrade to upstream's 2.3.0 merely for parity: the newer 2.4.1 dependency is deliberate and should remain unless a specific regression is found.

The available upstream source evidence did not establish an equivalent phone-side LADB workflow, the same two-stage Home/fully-drawn marker contract, or a directly reusable benchmark-readiness implementation. No unsupported claim of parity or superiority is made from that gap.

### Confirmed defect in our latest diagnostic reader

A source re-read found that `rootReadinessStateDescription()` called `UiObject2.getContentDescription()` while trying to inspect Compose's `Modifier.semantics { stateDescription = ... }`. These are different accessibility properties. AndroidX API documentation and implementation identify `getContentDescription()` as content description only; `UiObject2.getAccessibilityNodeInfo()` exposes the underlying `AccessibilityNodeInfo`, whose `getStateDescription()` is the correct accessor.

Corrected the diagnostic reader to:
- resolve the main-content root once;
- return a clear not-ready marker when absent;
- call `root.getAccessibilityNodeInfo().getStateDescription()`;
- distinguish a null state value from an inaccessible object;
- retain best-effort exception handling so diagnostics do not replace the original benchmark failure.

This correction changes diagnostics only. It does not change the Home marker, fully-drawn marker, `ReportDrawnWhen`, timeout budget, startup path or UI behaviour.

### Video/log interpretation — still-open evidence

The user's LADB recording demonstrates Home marker discovery but a timeout waiting for the separate fully-drawn marker. The dump failure on U+0000 is secondary. The `printf: Argument list too long` line is not mapped to a `printf` invocation at the matching line in the current remote runner, and the video stack/30-second timeout do not match the latest source's helper/60-second timeout. The exact APK and runner revision used for that capture therefore remain unproven.

The next run's provenance record (runner and APK SHA-256 plus instrumentation component) is the correct way to remove that ambiguity. Do not claim that the diagnostic correction resolves the original fully-drawn timeout or the old `printf` error until a fresh, provenance-matched phone capture demonstrates it.

### Upstream-informed improvement priorities

1. Keep comparing actual source/config, not README claims or apparent feature parity.
2. Keep UI Automator 2.4.1 unless a reproducible compatibility/performance regression justifies changing it; upstream's 2.3.0 pin is not evidence to downgrade.
3. Preserve both TTID and TTFD. Android's official Macrobenchmark guidance recommends measuring both initial and full display; never relax the fully-drawn gate to make profile collection pass.
4. Next instrumentation improvement should be a bounded timestamped stage timeline for app package present, Home marker, main-content stateDescription, and fully-drawn marker, plus resumed/active window package. Keep per-poll work low and do not add XML hierarchy dumps or unbounded shell-output arguments.
5. The old `printf` E2BIG source remains unknown. Use the staged script checksum and a narrow execution trace around the actual failing call on the next reproduction, rather than editing unrelated printf sites.

References:
- [Official Nuvio repository](https://github.com/NuvioMedia/NuvioMobile)
- [Official upstream version catalog](https://github.com/NuvioMedia/NuvioMobile/blob/cmp-rewrite/gradle/libs.versions.toml)
- [AndroidX UiObject2 API](https://developer.android.com/reference/androidx/test/uiautomator/UiObject2)
- [AccessibilityNodeInfo.getStateDescription](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getStateDescription())
- [Macrobenchmark startup measurement guidance](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)

No build, shell test, instrumentation test, device run or CI was started. The change is source-only and awaits the next authorized phone benchmark.



## 18 November 2026 — deeper performance research and next-cycle fixes

### Repository research reviewed

Re-read the startup benchmark investigation history and current sources it points to: the user's LADB recording and source/APK mismatch; the two-stage HomeScreen and ReportDrawnWhen contract; BaselineProfileGenerator.java, StartupBenchmark.java and StartupReadiness.java; androidApp/build.gradle.kts, baselineProfile/build.gradle.kts, the version catalog, phone runner, and source regression guard.

The last LADB capture remains an older artifact: its stack trace and 30-second helper timeout do not match the current Java shared helper and 60-second deadline. It cannot verify current source. Home appeared; the separately reported fully-drawn signal timed out; the U+0000 XML dump failure was secondary; printf: Argument list too long remains unmapped to an exact current runner call.

### Performance research — high-signal results

1. Keep TTID and TTFD separate. StartupTimingMetric measures TTID from launch intent to first rendered frame and TTFD through the Activity's reportFullyDrawn() signal. The UI Automator wait is a verification/profile-journey completion gate, not a replacement for the system metric.
2. Keep cold start as the primary optimisation target. Android's launch-time guidance says cold-start optimisation helps warm and hot starts too.
3. Startup Profiles are worth pursuing, but only when rules are included in the optimized release build and R8 actually applies DEX layout ordering. Android guidance says Startup Profiles can often improve startup 15–30% over Baseline Profiles alone. This is platform potential, not a measured Nuvio result.
4. Android documents validation of Startup Profile application through AAB metadata at BUNDLE-METADATA/com.android.tools/r8.json: at least one dexFiles entry should have startup: true, and DEX checksums should match the metadata.
5. Macrobenchmark's official comparison guidance includes no compilation, partial compilation with required Baseline Profile, partial compilation without Baseline Profile, and full compilation. These comparison modes separate profile benefit from general AOT/JIT effects; fully compiled results are not the expected first-install experience.
6. Baseline Profile journeys should mirror real high-value user journeys, and only app-entry flows should be included in Startup Profile. Do not add secondary navigation journeys simply to increase rule count.

Sources:
- [Android Startup Profiles and DEX layout optimizations](https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations)
- [Confirm Startup Profiles optimization](https://developer.android.com/topic/performance/baselineprofiles/confirm-startup-profiles)
- [Macrobenchmark compilation-mode comparison](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)
- [App startup time](https://developer.android.com/topic/performance/issues/launch-time)
- [Startup timing accuracy](https://developer.android.com/topic/performance/appstartup/analysis-optimization)
- [AndroidX BaselineProfileRule 1.5.0 source/API](https://android.googlesource.com/platform/frameworks/support/+/HEAD/benchmark/benchmark-macro-junit4/src/main/java/androidx/benchmark/macro/junit4/BaselineProfileRule.kt)

### Confirmed source configuration

- BaselineProfileGenerator.java calls the Java overload with maxIterations=15, stableIterations=3, output prefix startup, includeInStartupProfile=true and strictStability=true. The collector already opts its launcher journey into Startup Profile generation; no generator flag change was needed.
- benchmarkRelease inherits from release and explicitly keeps isMinifyEnabled=true and resource shrinking enabled. The profile-capture build type is intentionally non-minified.
- AGP is pinned at 9.4.0. Android guidance says DEX layout optimization is enabled by default from AGP 8.3, but produced AAB metadata still needs to prove startup rules were applied.
- The phone workflow currently exports APKs for installation and Macrobenchmark; Android's documented r8.json proof is in the AAB. This creates a verification gap: do not claim Startup Profile DEX ordering is verified from a non-empty startup-prof.txt alone. A retained AAB or reproducible metadata check on the release bundle is needed.

### Concrete fixes made in this cycle

1. Provenance is now parseable. The runner previously used printf format strings containing a literal backslash-n, making provenance a malformed logical line. It now emits real newlines and records app/test APK paths and SHA-256 values, in addition to runner hashes, flavor/action and instrumentation component.
2. Failure diagnostics expose root readiness state. The helper appends the main-content root's AccessibilityNodeInfo.stateDescription using the correct API.
3. Regression guards track the current contract. Removed an obsolete expectation for the former nuvio_readiness_home_ marker. The source guard now checks that Startup Profile collection remains enabled and benchmarkRelease inherits optimized release configuration with R8 enabled. It also checks newline-safe provenance and both APK hashes.

### Next highest-value work

1. Add missing benchmark comparison modes (Partial without Baseline Profile and Full) and validate output alongside current None/profile cases. Do this separately because the runner's JSON validation currently hardcodes the two existing cases and an exact two-result schema.
2. Retain or create an AAB artifact for the optimized release variant and add deterministic r8.json/DEX checksum validation. This proves Startup Profile DEX ordering; do not add speculative Gradle toggles instead.
3. Compare identical official and enhanced release variants on the same physical device, controlling account state, network, source config, build type, compilation mode and thermal state. Report TTID and TTFD P50/P90/P95, sample count and variability. Do not claim Enhanced outperforms official until the measurements show it.
4. Add bounded stage timestamps (target package visible, Home marker, root stateDescription, fully-drawn marker, active/resumed package) without 250ms per-marker full-tree scans. Preserve one monotonic deadline and current readiness semantics.
5. Continue isolating the old printf argument-size error. The malformed provenance was one confirmed runner defect, but it does not prove it caused the previous E2BIG error.

### Verification boundary

Remote source review confirms the edits landed. No shell regression script was executed, no Kotlin/Java compilation was run, no APK/AAB was built, no LADB run was started, and no CI was triggered. Runtime and performance benefits are unverified. The next device run should use the new provenance format and record APK hashes before conclusions are drawn.


## 18 November 2026 — ART runtime diagnostics added to the phone benchmark

### New research finding

The AndroidX Macrobenchmark 1.5.0 source contains `ArtMetric`, which captures app-process ART events during a benchmark:
- `artJitCount` / `artJitSumMs`: methods JIT-compiled at runtime;
- `artVerifyClassCount` / `artVerifyClassSumMs`: classes verified at runtime;
- `artClassLoadCount` / `artClassLoadSumMs`: classes loaded at runtime, only when the device's API/ART version exposes the relevant tracepoints.

Important interpretation limits from AndroidX source:
- ART JIT may include framework code from the system image, even for a profile-compiled app.
- Class-load metrics may be absent on unsupported ART versions; absence is not proof of zero work.
- High runtime verification can indicate install-time verification did not happen for some classes; debuggable applications are not verified at install time, one reason release/profileable builds are required.
- A non-zero JIT total alone is not proof of incomplete Nuvio profile coverage.

Sources:
- [AndroidX ArtMetric source](https://android.googlesource.com/platform/frameworks/support/+/HEAD/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Metric.kt)
- [AndroidX ArtMetric API](https://android.googlesource.com/platform/frameworks/support/+/155b9eedc7a9732cce0505b492b2c15f5e70d1b9/benchmark/benchmark-macro/api/current.txt)
- [Android performance test environment](https://developer.android.com/topic/performance/appstartup/setup-env)

### Concrete implementation

Added a separate `startupArtDiagnostics` instrumentation case to `StartupBenchmark.java`. It uses the exact same cold launch, app package, readiness wait, ten iterations and required packaged Baseline Profile as the profile timing case, but adds `ArtMetric` beside `StartupTimingMetric`.

The original two timing cases remain unchanged and continue to produce the exact JSON names/shapes consumed by the strict phone-runner TTID/TTFD parser. The ART case is validated as an additional test method in the instrumentation log, but its optional device-specific ART metrics do not alter the original timing statistics or trace-count expectations.

The phone runner now requires the ART diagnostic case to execute. The source regression guard protects the presence of that case and its `ArtMetric` configuration; a duplicated source-path assignment in the guard was also removed.

### How to use the new evidence

Compare the ART diagnostic result with the required-profile timing run:
- large Nuvio-process JIT counts/duration suggest important executed methods still run interpreted or are outside the shipped profile;
- runtime class verification can indicate verification was deferred for some classes;
- class-load values are meaningful only when reported by the device's ART version;
- correlate candidate methods/classes with Perfetto traces and the generated `startup-prof.txt` before editing application code.

Do not automatically convert ART totals into a pass/fail threshold. They can include framework activity and ART-version differences. Their purpose is to identify what to inspect next.

### Verification boundary

The source and runner changes have been written to `main-build-testing` and will be re-fetched for confirmation. No build, instrumentation execution, LADB run, test script or CI was started. Compilation compatibility and runtime JSON output remain unverified until the next authorized benchmark build/run.


## Official Nuvio Mobile startup/profile comparison — 2026-11-18

### Sources inspected

The official Nuvio Mobile repository is `NuvioMedia/NuvioMobile`, branch
`cmp-rewrite`. The strongest source is merged PR
[#2652 — perf: smoother cold start and home on Android](https://github.com/NuvioMedia/NuvioMobile/pull/2652),
merged 2026-11-14 at merge commit
`e5272769a6709d43aaecc3d7bbe8d66d5a200702`. This PR contains its own
maintainer-authored investigation and numerical before/after results.

### Official maintainer's published measurements

The PR states that measurements were taken on a Xiaomi 120 Hz phone, with
interleaved base/PR runs. Startup/Perfetto values use four cold starts per
side. Other frame metrics use scripted `dumpsys gfxinfo` runs.

| Metric | Base | PR result |
| --- | ---: | ---: |
| Main-thread recomposition time in first 4.5s of cold start | 244–324ms | 90–120ms |
| Frames exceeding 8.3ms in same window | 8–11 | 2–5 |
| Worst launch-splash frame | 47–60ms | 25–34ms |
| MainAppContent recompositions in first 10s | about 17 | 3–4 |
| HomeScreen recompositions in first 10s | about 85 | 38–51 |
| Details open/back p99 frame | 34ms | 32ms |
| Home scroll p95 frame | 7.3ms | 7ms |

These are maintainer-reported results, not independently reproduced here.
The published table is about recomposition/frame behavior, not a TTID/TTFD
before/after table and not an isolated measurement of Baseline/Startup Profile
contribution.

### Official implementation found

At `cmp-rewrite`, official `baselineprofile/build.gradle.kts` configures a
self-instrumenting `:baselineprofile` test module and
`useConnectedDevices = true`. The generator source is
`baselineprofile/src/main/kotlin/com/nuvio/baselineprofile/BaselineProfileGenerator.kt`.

The generator has two `BaselineProfileRule.collect` passes:

1. `includeInStartupProfile = true`: launcher cold-start path only.
2. `includeInStartupProfile = false`: launch plus Home vertical/horizontal
   scrolling, hero details, poster details and Search/Library/Profile/Home tabs.

Both use `maxIterations = 3` and `stableIterations = 2`. The generator uses
`com.nuviodebug.com`, waits up to 15 seconds for the app package window, then
sleeps 6 seconds after launch. Detail journeys use fixed settle delays.

Important reliability caveat: the official `runJourney` helper catches
`Throwable`, logs a warning and continues. Missing targets or journey errors
can therefore be logged without failing the outer collection. The declared
journey coverage is not proof that every step succeeded on every collection.
This is a concrete opportunity for Enhanced to provide more reliable journey
completion evidence, not a reason to blindly copy the official implementation.

Official `androidApp/build.gradle.kts` uses a dedicated `benchmark` build type
initialized from release, with minification and resource shrinking enabled,
debug signing, and an isolated `com.nuviodebug.com` package ID. It enables
`saveInSrc`, `mergeIntoMain`, and disables automatic generation during build.
The version catalog pins AGP 9.2.0, AndroidX Benchmark/Baseline Profile 1.5.0,
and UI Automator 2.3.0.

Generated profile artifacts could not be reliably fetched from the current
public branch path in this review. Do not infer the current rule count, profile
size, exact rule distribution, or DEX placement from search snippets or
unverified historical paths.

### Enhanced branch comparison

Remote `main-build-testing` was compared with base
`371106f5df4bdd9f168fffa402cb829552eb0ef4`: 17 commits ahead, 0 behind at
the time of this check. This is the fork's own branch comparison, not a
comparison against the official Nuvio repository.

Enhanced currently:
- Collects one startup-only journey with `includeInStartupProfile = true`,
  `filterPredicate = rule -> true`, 15 max iterations, 3 stable iterations.
- Uses a shared readiness helper requiring Home marker and the app's actual
  fully-drawn marker under one monotonic time budget.
- Measures ten cold launches in separate no-compilation and
  `Partial(BaselineProfileMode.Require)` cases.
- Has a separate `ArtMetric` diagnostic case under the profile-enabled
  compilation mode.
- Builds an isolated `benchmarkRelease` with release optimization/R8 enabled
  and `nonMinifiedRelease` for profile capture.

Enhanced's stronger readiness gate is a meaningful measurement-contract
difference from the official package-window + fixed-delay wait; it is not
evidence of faster startup. Enhanced's broader 15/3 startup collection settings
also do not prove a better quality profile than official 3/2 settings.

### Research conclusions and priority

1. **First priority — get a fair profile coverage comparison.** Add explicit,
   observable post-startup journeys to Enhanced as a separate
   `includeInStartupProfile = false` collection pass, preserving the
   launcher-only Startup Profile. Every required journey must fail visibly
   when its destination/interaction is unavailable; don't copy official
   catch-and-continue behavior. The exact Enhanced journeys should align to
   actual app entry points and be independently verifiable. Do not add detail,
   tab, or scroll code to the Startup Profile pass.
2. **Second priority — inspect compiled profile and DEX outputs.** Compare
   actual generated Baseline/Startup rule files, rule counts and package
   distribution, compiled `assets/dexopt/baseline.prof`, and AAB
   `BUNDLE-METADATA/com.android.tools/r8.json`. Verify a DEX entry marked
   `startup: true` and checksum matching the corresponding DEX. Profile text
   line count/size alone does not establish quality or successful Startup
   Profile DEX placement.
3. **Third priority — make the benchmark comparison like-for-like.** Retain
   TTID and TTFD, ten iterations, identical cold-start/sign-in state, APK
   optimization, device/OS build and thermal conditions. Add the Android
   documented Partial-without-Baseline warmup (3 iterations) and Full
   compilation as diagnostic/reference modes when the runner output contract
   can represent them without misparsing. Keep Full labelled as less
   representative of fresh installs.
4. **Fourth priority — investigate app-code causes using traces.** Official
   PR #2652 gives concrete leads: whole-state collection triggering broad
   recomposition, synchronous splash asset decode, and repeated per-entry
   hidden-show lookup. Inspect Enhanced's corresponding data flows and profile
   traces; only change code where a real equivalent hotspot or measured trace
   supports it. The official PR's exact fixes must not be assumed already
   present in the current Enhanced tree.

### AndroidX 1.5.0 documentation findings

AndroidX Benchmark 1.5.0 is the stable release (released 2026-09-09; release
page updated 2026-10-07). Android's current documentation recommends four
comparison modes: None, Partial requiring Baseline Profile, Partial with
Baseline Profile disabled and three warmup iterations, and Full. Full is useful
as a lower-JIT/noise diagnostic but is not a fresh-install representation.

`ArtMetric` reports `artJitCount`/`artJitSumMs`,
`artVerifyClassCount`/`artVerifyClassSumMs`, and on supporting devices
`artClassLoadCount`/`artClassLoadSumMs`. Class-load tracepoints require API
35, or API 31+ with ART mainline >= 341511000. Unsupported class-load
measurements are omitted, not necessarily emitted as zero. JIT data may include
system-image framework methods even with partial/full compilation.
`ArtMetric` is diagnostic evidence, not itself a user-visible performance
metric. Benchmark 1.5.0 also includes a fix for class-load tracking under
top-level R8 packages / `-repackageclasses`, so the version pin matters.

### Verification boundary

No build, unit test, shell regression, instrumentation, Macrobenchmark, phone,
LADB or CI workflow was run for this review. All code/config observations above
come from fetched GitHub source. No claim is made that Enhanced currently beats
official Nuvio. A matched-device measurement and profile artifact inspection
remain necessary.


## Follow-up source-level comparison — mapped Compose state and profile collection reliability — 2026-11-18

### Official mapped-state helper inspected

Official source:
[MappedState.kt](https://github.com/NuvioMedia/NuvioMobile/blob/cmp-rewrite/composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/MappedState.kt)
(blob `fbf1e1ad2787aabfd836efd7afbb7fd43b718415`).

Its implementation is:

```kotlin
@Composable
fun <T, R> StateFlow<T>.collectMappedAsState(transform: (T) -> R): State<R> {
    val flow = remember(this) { map(transform).distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = transform(value))
}
```

This detail matters: it is not useful to append `distinctUntilChanged()` directly to a `StateFlow`. Kotlin's StateFlow documentation explicitly says StateFlow already uses equality-based conflation and that applying `distinctUntilChanged()` directly to a StateFlow is operator-fused/no-op. The official helper first maps the broad repository state to a smaller feature-facing value, then deduplicates the mapped output. It also remembers the mapped Flow by the source StateFlow instance so recomposition does not construct a new Flow chain each time.

Official Kotlin references:
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/distinct-until-changed.html

AndroidX Compose's `collectAsStateWithLifecycle` starts collection when lifecycle is at least STARTED and stops it below that state. The official helper therefore combines narrow projection, equality filtering, stable Flow identity and lifecycle-aware collection.

### Enhanced code comparison

The current Enhanced `main-build-testing` source was fetched:
- `MainAppContent.kt`, blob `1a699f30d4516bd2c0b61aabe3c568178b70cc5e`.
- `HomeScreen.kt`, blob `dabb27b3784b463b44aeec40295a53ae6159689f`.
- `HomeRepository.kt`, blob `781ae95bbd7813261266909ac6a24de5adb69505`.
- `AddonRepository.kt` was also fetched and confirms `uiState` remains a broad StateFlow of `AddonsUiState`.

The inspected Enhanced code still directly calls `collectAsStateWithLifecycle()` for several full repository UI states, including:
- In `MainAppContent`: `MetaScreenSettingsRepository.uiState` and other root-owned repository state collections.
- In `HomeScreen`: `AddonRepository.uiState`, `HomeRepository.uiState`, `HomeCatalogSettingsRepository.uiState`, `CollectionRepository.collections`, Continue Watching preferences, watched state, and watch-progress state.

The exact call-site inventory is larger than the excerpts captured in this remote fetch; re-read the relevant function ranges before editing any call site.

Enhanced already has other optimizations that should be preserved:
- Home catalog definition construction uses `withContext(Dispatchers.IO)`.
- The Home repository maintains an addon catalog refresh signature to avoid equivalent refresh work.
- Progressive Home catalog state publication is throttled by `HOME_CATALOG_PUBLISH_THROTTLE_MS`.
- Continue Watching/Next Up processing includes caching/memoization improvements from previous work.

These improvements do not automatically eliminate broad UI invalidations from repository StateFlow updates. A narrow mapped-state projection is therefore a credible candidate, but it is not yet measured as an Enhanced bottleneck on device.

### Safe implementation target

The best next code cycle is not to replace every StateFlow collection indiscriminately. Instead:

1. Identify the largest state objects collected at the `MainAppContent` and `HomeScreen` roots, then list exactly which fields each composable actually reads.
2. Define immutable, minimal feature-specific projection values for the high-churn sources (especially addon signals and any broad root-level repository state that changes during startup).
3. Collect the derived flow via `remember(source) { source.map(transform).distinctUntilChanged() }`, then collect it using the platform-appropriate lifecycle-aware Compose collector.
4. Keep state needed by downstream UI available without dropping behavior: every field read by the screen must be represented, and all existing side effects must stay triggered by equivalent dependencies.
5. Add behavior-level regression tests for equality suppression and for updates to each field the UI depends on. Test the projection's observable output, not source-text presence.
6. Only after the regression tests are run and pass should a cold-start Perfetto/Macrobenchmark comparison establish whether recomposition time and frame-budget misses improve.

Avoid annotating broad mutable collection models as `@Stable` or `@Immutable` just to force skipping. Android Compose guidance warns that collection stability, equality semantics and copying cost must be considered; over-annotation can produce stale UI or expensive equality checks. Narrow projection is more defensible than falsely asserting mutability guarantees.

### Official generator reliability finding

The official `BaselineProfileGenerator.kt` at `cmp-rewrite` is confirmed to have two `collect` passes (startup only, then non-startup CUJs). Its `runJourney` helper catches `Throwable`, logs and continues; individual missing or failed journeys can therefore escape as warnings rather than failing profile generation. This makes source-level journey intent insufficient proof of actual collection coverage.

Enhanced should not imitate this failure suppression. When a future non-startup profile pass is implemented, each expected journey should expose a concrete completion assertion; absent targets, unexpected screen transitions and interaction failures must fail collection. A run summary should identify each journey and its outcome.

### Additional Benchmark result-contract finding

Enhanced's phone runner currently validates the two timing cases with ten TTID/TTFD samples each and twenty unique Perfetto trace references. The separate ART diagnostic case has a separate test name, but the current timing JSON parser is intentionally strict about the two timing method names and associated metric objects. Do not broaden or relax this parser when adding profile journeys or benchmark modes. ART metric fields require a separate optional diagnostic parser/summary; class-load keys can be absent on unsupported ART versions, while JIT and verify count/sum values are emitted as zero when no slices are present.

AndroidX 1.5.0 source (Context7) confirms:
- `ArtMetric` emits `artJitCount` and `artJitSumMs`, plus `artVerifyClassCount` and `artVerifyClassSumMs`.
- `artClassLoadCount` and `artClassLoadSumMs` are omitted if class-load tracing is unsupported.
- Class-load tracing is available on API 35+, or API 31+ when ART mainline is unknown or at least 341511000.
- JIT traces can include system-image framework code and do not all represent app profile misses.

### Next engineering priorities

1. Establish a field-by-field projection map for Home and root Compose collectors, starting with the most frequently updated large state (addons) and prove the UI only reads the projected data at the selected call sites.
2. Add a separately collected non-startup Baseline Profile journey suite with explicit per-journey success and no swallowed failures. Keep `includeInStartupProfile = true` strictly limited to paths needed for initial usable Home.
3. Extend the report format for ART metrics separately from TTID/TTFD timing metrics. Missing class-load fields mean unsupported tracepoints, not zero.
4. Obtain and inspect generated official and Enhanced profile artifacts. The current public lookup still did not provide the official generated profile files, so official text-rule counts, package distributions and binary rule sizes remain unknown.
5. Do matched-device performance measurements for actual superiority claims. No static source comparison can demonstrate that Enhanced is faster.

### Verification boundary

No test, shell script, Gradle build, instrumentation, device benchmark, LADB session, or CI was run. No production Kotlin implementation was changed during this follow-up. This update is source research and a concrete prioritized plan only.

## Deep startup-path investigation and non-copycat optimization priorities — 2026-11-18

### Existing research reviewed

Reopened the current engineering note rather than relying on previous conversation summaries. It already contains the original LADB failure and artifact provenance, Home/fully-drawn readiness contract, strict benchmark runner output contract, official Nuvio PR #2652 findings, mapped-state comparison, and historical CI results with explicit source/bundle mismatch caveats. Historical APK bundles must not be conflated with current branch source; every new device run needs a source/build hash match.

### New source inspection: Enhanced app startup order

Current main-build-testing files fetched:
- MainActivity.kt — blob 6c4a9d4d3a360d33023ae4114bb522412c6bb997.
- NuvioApplication.kt — blob e2b48a76898ba97a56d40900c1e12a6ba87a65db.
- StartupPreloader.kt — blob df20de751d8d65aeebfda6a0b2c7391321c0b5bb.
- HomeScreen.kt — blob dabb27b3784b463b44aeec40295a53ae6159689f.
- ProfileRepository.kt — blob ef46d35b502beea02b211fd5b66305e3e1548a7a.
- WatchedRepository.kt — blob b0c695d43c2f1871a16731189b46cfbf9839aaaf.
- WatchProgressRepository.kt — blob da19e850ac9bc78b737d335e09cfe04e982e0493.
- ContinueWatchingPreferencesRepository.kt — blob 25dedecaebbcaa9b7a54c10c76bdbc845945fcf1.
- DownloadsPlatformDownloader.kt — blob c61590031300ee3ee0e2653847643df82e8aa5d7.

#### MainActivity critical path

The current Activity does the following before showContent() and Compose setContent:
1. Installs SplashScreen with setKeepOnScreenCondition { !contentReady }. AndroidX docs say this condition is evaluated periodically on the main thread.
2. Performs a long sequence of initialize(applicationContext) calls covering theme/icon/Sentry settings; auth and server configuration; addon, library, Live TV, app file cache and watched storage; player/seek preview/P2P; profile/avatar/PIN; search and settings stores; tracking providers; download infrastructure; updater; account cleanup and episode notification platform.
3. Starts/awaits StartupPreloader's critical phase before calling showContent() when preloader is not ready.
4. showContent() calls setContent, and the first-frame LaunchedEffect starts post-frame warmup only after withFrameNanos { }.

This long synchronous initializer sequence is a plausible cold-start cost centre, but its duration and main-thread blocking are not known from source alone. Some initialize methods may be cheap object wiring; others may touch disk, services, native libraries or platform APIs. The initializer list length is not a performance measurement.

The app Application.onCreate itself currently calls DnsPlatform.initialize(this); Coil's custom ImageLoader is constructed lazily via SingletonImageLoader.Factory and configured with OkHttp, DNS-related shared clients, image decoders and interceptors. This pipeline also deserves trace inspection, but should not be eagerly preloaded without evidence that first Home artwork is delayed by it.

#### StartupPreloader critical and post-frame contracts

The preloader is already partially parallelised:
- DownloadsPlatformDownloader.prepareForStartup() begins on Dispatchers.IO.
- Profile cache load runs through ProfileRepository.loadCachedProfiles(), which itself switches to Dispatchers.IO to decode the stored profile payload.
- Watched repository, watch progress, Continue Watching preferences, and Home snapshot are loaded in an IO context.
- It waits for the download-platform preparation before marking critical preload complete.
- Post-frame phase loads download transfer store + DownloadsRepository, then Live TV, player settings, P2P settings, servers, meta settings and LibraryRepository in IO context.

The preloader's step helper catches Throwable (while rethrowing cancellation), logs a warning and returns false. preloadCriticalPhase currently does not enforce the returned booleans; so failed profile/watched/progress/preferences/snapshot preparation can still result in the critical phase completing. The current isCriticalReady means the critical coroutine completed, not that every preload item succeeded. This matters for any readiness semantics change.

HomeScreen also calls AddonRepository.initialize(), CollectionRepository.initialize(), ContinueWatchingPreferencesRepository.ensureLoaded(), WatchedRepository.ensureLoaded(), and WatchProgressRepository.ensureLoaded() in a LaunchedEffect(Unit). It directly collects corresponding state flows. These calls may be idempotent after preload, but the actual Android storage paths and ensureLoaded guards must be inspected before making scheduling changes.

ContinueWatchingPreferencesRepository.ensureLoaded() is a synchronous function that can call loadFromDisk(), decode JSON, and update a StateFlow. It should not be called on the main thread before checking whether it is already loaded and what its storage adapter does. In this Activity's first screen flow, the preloader invokes this method from its IO context.

ProfileRepository.loadCachedProfiles() explicitly uses Dispatchers.IO around payload decoding and has a mutex/generation guard. It should not be made asynchronous without preserving its generation/session protections and the active-profile initialization ordering.

The Home skeleton logic is already driven by shouldShowInitialHomeLoading(hasRenderableHomeRows, addonManifestsLoading, homeCatalogLoading), and the hero slot by shouldShowHomeHeroSlot(...). This suggests the UI has loading/skeleton pathways, but it does not prove that profile, watched or playback-progress data can be deferred without changing initial Home or resume-prompt behavior.

### New profiling hypothesis: time the real launch barriers before changing them

Best next diagnostic is to capture the duration of real app startup phases rather than immediately deferring or removing initializers.

Candidate trace sections (only once trace API signature/dependency is verified at stable versions):
- Activity: Nuvio.Startup.Activity.PreSetContent, plus grouped sub-sections around storage groups and platform/network/native initializers.
- Preloader: Nuvio.Startup.Preload.Critical, ProfileCache, Watched, WatchProgress, ContinueWatchingPreferences, HomeSnapshot, DownloadsPlatform, and post-frame warmups.
- App root: Nuvio.Startup.AppGate.InitializeAuth, RuntimeServices, MainContentFirstComposition, and first Home readiness transitions.

Use stable, low-overhead trace instrumentation and Macrobenchmark trace section metrics only after checking the exact stable API signature and dependency version. The Context7 lookup returned 1.5.0-RC source for some TraceSectionMetric constructors; that is not accepted as final evidence for stable 1.5.0. No new dependency should be added without approval. Android platform android.os.Trace or an already-pinned stable AndroidX tracing library may be considered only after exact version/API verification. Trace instrumentation must pair begin/end in finally blocks and not materially alter startup.

Benchmark TTID/TTFD and per-phase trace durations on the same release-optimised APK and physical device. Compare p50/p90, not single runs. A long async operation can still hurt first frame through CPU/I/O contention, so its duration and thread must be inspected rather than assuming Dispatchers.IO makes it free.

### Non-copycat optimization strategy vs official Nuvio

Official PR #2652 reports improved startup mainly through reduced recomposition, off-main splash wordmark decode, memoized hidden-show lookups and throttled Next Up publication. Enhanced already has partial equivalents: Home catalog preparation offloaded to IO; Home catalog publish throttling; addon refresh signature/cache work; Continue Watching and Next Up processing/caching improvements; readiness gates and startup/profile benchmark infrastructure.

The stronger Enhanced opportunity is to reduce pre-Compose blocking and unnecessary eager platform initialization, then use mapped state to limit Home invalidation. This is different from simply reproducing official code.

Priority A — quantify Activity/preloader critical path using trace sections and strict startup TTID/TTFD. Do not change behavior yet.

Priority B — after traces establish a measurable slow stage, classify each initializer by consumer: required before first Activity content and initial Home; required before fully drawn/interactive Home; can safely happen after first frame; or can be initialized only when the matching feature is opened. Any move must preserve account/profile switching, deep links, playback startup, player/download state, notification permission/binding, and background process entry points.

Priority C — separate blocking storage preparation from pure object initialization only where source shows disk/network/binder/native work. Avoid moving mutable singleton initialization off-main if the implementation depends on main-thread ordering or has unsafe races.

Priority D — narrow high-churn StateFlow projections for Home/root state after exact field-use inventory and behavior tests. Do not mechanically wrap every StateFlow.

Priority E — implement additional non-startup profile CUJs only when their selectors and screen outcomes can be asserted reliably. Keep the Startup Profile strictly limited to the critical initial display path. The official generator's catch-and-continue journey helper is not a quality bar.

### Official Android guidance checked

Android developer documentation:
- App startup analysis: identify long operations in Perfetto (e.g. >20ms), main-thread blocking, disk I/O, binder transactions and work that can move after first frame; use StrictMode on debug builds to expose main-thread disk/network I/O; optimize and re-measure. https://developer.android.com/topic/performance/appstartup/analysis-optimization
- App launch/TTID/TTFD: cold start includes Application and Activity creation; Activity.onCreate commonly affects startup; TTFD must represent actual interactive readiness. https://developer.android.com/topic/performance/issues/launch-time
- Best practices: lazy initialization for non-critical libraries, defer optional composition until later. https://developer.android.com/topic/performance/appstartup/best-practices
- App Startup supports lazy initialization by disabling eager auto-init and calling AppInitializer for components only when needed; not a recommendation to migrate arbitrary hand-written initializers automatically. https://developer.android.com/topic/libraries/app-startup

### Measurement caveat around ProfileInstaller

AndroidX ProfileInstaller's Startup initializer deliberately schedules profile installation after the first frame and performs the actual write on a background thread. Its source warns against invoking profile installation during app initialization because it starts a thread and disk read/write immediately. Do not manually add an eager ProfileInstaller.writeProfile call to the critical Activity startup path. The AndroidX initializer's delayed strategy is preferable to forcing install during launch.

### Candidate architectural risks ruled out for now

- Do not remove the 60s startup-ready deadline or loosen the Home/fully-drawn gates.
- Do not make profile, watched, or watch-progress preload optional until Home/Continue Watching/Resume Prompt consumer requirements are mapped.
- Do not start P2P streaming engine lazily without checking whether initial Home/feature routes read its availability state and whether start races with playback.
- Do not defer notification platform binding without checking process/service/permission callback lifecycle.
- Do not add a new tracing dependency before checking the current catalog and exact stable API.
- Do not add additional benchmark test methods that would break the runner's strict two-case JSON and Perfetto trace reference validation.
- Do not use Startup Profile rules to cover post-startup browse interactions; those belong in Baseline Profile only.

### Verification boundary

This pass made no Kotlin, Java, Gradle or script source changes. It updated only the research note. No shell tests, Gradle tests, build, APK/AAB generation, instrumentation, Macrobenchmark, device run, LADB session, or CI workflow was run. The startup-cost centres identified above are source-level candidates, not measured bottlenecks. A trace-backed investigation is required before any initialization order change or performance claim.
## Follow-up: official profile evidence, Compose trace plan, and mapped-state audit — 2026-11-18

### Official Nuvio release/profile evidence (mobile only)

Revisited official Nuvio Mobile release tag 0.6.13-beta (commit 6b7f15e) and current cmp-rewrite source, and filtered out NuvioTV results. Release 0.6.13-beta lists PR #2652 as 'perf: smoother cold start and home on Android'; official 1.0.0 release notes say it has the same features/fixes as 0.6.13-beta. The PR's published metrics are from the maintainer's measurements, not independently reproduced here.

Generated Mobile profile files (baseline-prof.txt/startup-prof.txt), compiled baseline.prof, or App Bundle r8.json for the official repository could not be retrieved/verified in this pass. Search results for 22k-26k profile line counts referred to NuvioTV's Android TV app and have been explicitly excluded. Do not attribute NuvioTV profile size/rule counts to Nuvio Mobile.

The official Mobile generator source at release commit 6b7f15e is confirmed to make two collect passes: launcher-only with includeInStartupProfile=true and browse journeys with includeInStartupProfile=false. It uses maxIterations=3 and stableIterations=2. The browse run covers Home vertical/horizontal scroll, hero details, poster details and Search/Library/Profile/Home tabs. It uses package-window wait + fixed 6s and 3s settle delays. runJourney catches Throwable, logs and continues; that means collection success does not guarantee every journey ran successfully.

Official source also confirms mapped state in active app code, not merely a helper file:
- HomeScreen maps AddonRepository.uiState into HomeAddonSignals using enabled addons, then collects only this projection at that call site.
- MainAppContent maps poster-transition setting, active profile / overlay profile, library source mode, stream auto-play policy, network condition and completed downloads into narrow derived values at different call sites.
- Broad state is still collected where the composable needs broad state. The official pattern is selective field mapping, not a wholesale rewrite.

Official HomeScreen's addon projection is feature-specific: it derives compact signals from enabled addons and uses that signal in refresh/readiness behavior, while full HomeRepository state remains broad. Do not assume their exact projection fields can be copied into Enhanced; first inspect all Enhanced reads and preserve its local behavior.

### Android Compose tracing is a concrete next diagnostic

Research verified these primary Android references:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args
- https://developer.android.com/develop/ui/compose/tooling/tracing
- https://developer.android.com/reference/android/os/Trace

AndroidX Benchmark 1.5.0 is pinned in Enhanced (libs.versions.toml). Its Macrobenchmark documentation exposes TraceSectionMetric, an experimental metric, to measure named trace sections and counts, including sections produced by android.os.Trace.beginSection/endSection. A current official AndroidX package reference lists modes Average, Count, First, Max, Min and Sum; however Context7 source for the exact TraceSectionMetric constructor in stable 1.5.0 was incomplete/RC-biased during this pass. Before writing it into Java benchmark code, obtain the exact 1.5.0 API signature from the versioned artifact/tag, not an RC snippet.

Android platform Trace.beginSection/endSection has been verified via Android API docs. Sections are synchronous and MUST begin/end on the same thread; names must be <=127 Unicode code units; nesting must be balanced. Trace.beginAsyncSection/endAsyncSection is appropriate for spans crossing dispatchers, with matching name/cookie and unique cookies for overlapping spans. Avoid holding a synchronous trace section open across coroutine suspension or dispatcher changes.

Compose composition tracing (not high-overhead recomposition tracing) can add composable work to Perfetto. To capture it under Macrobenchmark, Android's docs specify androidx.tracing:tracing-perfetto and tracing-perfetto-binary dependencies in the benchmark module and instrumentation arg androidx.benchmark.fullTracing.enable=true. The Compose compiler tracing strings are already added by Compose compiler 1.3+, while the runtime-tracing library is required for Compose runtime trace output. The enhanced catalog currently shows Compose Multiplatform 1.13.0-beta01 but does not declare a runtime-tracing dependency. Need verify the exact JetBrains/AndroidX matching artifact coordinate for this KMP Compose version before adding anything. No dependency has been added.

Android's docs explicitly warn that recomposition tracing captures state reads/writes and stack information with significant overhead; use it to diagnose causes, not as the official headline performance number. Keep clean timing runs without recomposition tracing and use separate diagnostic runs when possible.

### Enhanced mapped-state source audit and exact fields

Enhanced sources re-fetched:
- AddonModels.kt blob 606a5200fe820a7987e6d315e4ea809c4c998884.
- AddonRepository.kt blob b320ffc4d8e461a3e86adaf71299d950883b092b.
- HomeScreen.kt blob dabb27b3784b463b44aeec40295a53ae6159689f.
- MainAppContent.kt blob 1a699f30d4516bd2c0b61aabe3c568178b70cc5e.
- Version catalog blob 0ec2bb5c69dab565b6d7d74d8b466637d26385fc.

HomeScreen currently collects the full AddonsUiState. The actual Home-facing requirements include:
- enabled addon values for HomeRepository.refresh() when network reconnects;
- enabled/pending manifest state for initial Home loading/skeleton decisions;
- the first enabled manifest error for Home error presentation;
- enabled addon manifests/metadata required to assemble and refresh catalog definitions.

Therefore a projection containing only enabled addon list is incomplete and can break skeleton/error/readiness behavior. A potential HomeAddonSignals immutable projection needs a complete consumer audit and tests covering every field that affects: enabled addon refresh input, has pending enabled manifests, first enabled manifest error, and catalog manifest readiness. To avoid large equality cost, prefer small primitive/list projections with stable ordering and avoid repeatedly copying complete manifests or catalogs unless a consuming path needs them. Full catalog data may remain in a separate state pipeline.

MainAppContent directly collects full AddonRepository.uiState because it consumes several fields for profile-aware addon operations and UI/sync decisions. A narrow root projection cannot be designed from the partial code slice inspected; inventory every read in the whole composable before considering it. Some handlers can read the repository's current `.value` on demand to avoid making root composition depend on every update, but only if they don't need reactive UI updates and do not change closure semantics.

Potential safe first implementation after authorization to run tests: create pure projection data class/helper in AddonModels.kt; add unit tests proving each required Home signal changes only for relevant source updates and preserves enabled addon order; add test that a disabled addon update with unchanged Home signals does not emit a new projection; then wire only HomeScreen to collectMappedAsState using `remember(source) { source.map(transform).distinctUntilChanged() }` and collectAsStateWithLifecycle. Avoid root and other screens until independently mapped.

Current Kotlin Coroutines catalog pins 1.10.2; Context7 resolved the official Kotlinx Coroutines source. StateFlow itself conflates upstream values by Any.equals and direct distinctUntilChanged on a StateFlow is fused/no-op. The useful equality boundary is *after* mapping broad state into an independent smaller value. StateFlow emits current state immediately to collectors and may skip intermediate fast updates for slow collectors, always delivering the latest. Derived projection equality can suppress irrelevant downstream recompositions.

### Benchmark/profile strategy updated

1. Establish fair and reproducible startup baseline: unchanged 10-iteration cold-start TTID/TTFD timing tests, same signed-in profile state, same optimization configuration and physical device.
2. Add separate diagnostic benchmark/trace collection only through a report-contract-safe path. Existing phone runner strictly validates the two timing method names, their two startup metrics, and twenty trace files. Keep any diagnostic trace/report output clearly independent instead of weakening timing validation.
3. Add named activity/preloader phases with safe sync/async trace instrumentation: Activity pre-setContent initializers; preloader profile/watch/progress/preferences/home snapshot/download prep; AppGate auth/runtime; first Home composition/readiness. Keep diagnostic traces separate from headline timing runs if the tracing dependency or instrumentation changes overhead.
4. Inspect Macrobenchmark 1.5.0 TraceSectionMetric exact stable API before adding metrics; use Perfetto slices for named app trace spans and CPU/thread analysis.
5. Compare compiled Enhanced profile: text baseline-prof.txt/startup-prof.txt; binary assets/dexopt/baseline.prof; AAB BUNDLE-METADATA/com.android.tools/r8.json `dexFiles` with `startup: true` and matching DEX checksum; check primary classes.dex startup placement and profile size under documented 1.5 MB binary-profile packaging limit. Compare official only if we can obtain the equivalent official artifacts; do not substitute TV files.
6. Add browse CUJs to Baseline Profile only, with explicit per-journey screen/assertion outcomes and no catch-and-continue. Keep launcher / initial Home readiness in Startup Profile only.

### Conclusions

The most promising performance improvement is still selective Compose state projection and reducing measured startup critical-path work, not indiscriminately enlarging profile rules. We now have stronger proof that official Nuvio uses mapped state at multiple large/root/UI boundaries, while Enhanced has some relevant broad collections. However, the precise Home addon projection requirements show why this needs a field-by-field design and regression coverage rather than a copy/paste helper.

Official generated Mobile Baseline/Startup Profile artifact contents remain unavailable; official rule counts/package distribution/DEX placement remain unknown. Nuvio's PR #2652 reported frame/recomposition wins; it did not publish independent matched-device Macrobenchmark TTID/TTFD profile A/B results in the evidence retrieved here.

### Verification boundary

No production Kotlin/Java/Gradle/script changes were made in this pass. No tests, shell scripts, Gradle tasks, APK/AAB build, instrumentation, Macrobenchmark, phone, LADB or CI were run. The note is the only intended repository change.
## Follow-up: expanded Android/Compose research and startup source audit — 2026-11-18

### Research coverage and source-quality boundary

Reopened the existing 330 KB note and the current branch's Activity, Application, Android manifest, benchmark/profile generator, root/Home composables, addon models and addon tests. Context7 was available for Compose and Coroutines. The connected Exa search provider returned HTTP 402 (credits limit) on the first broad multi-angle query; no new Exa results were available in this cycle. Do not claim an Exa sweep occurred or present stale snippets as new findings. Exa can be resumed after its account quota is restored.

### New Compose performance findings from Android documentation

Context7 selected official Android Compose reference docs. Useful documented facts:
- Compose stability annotations are contracts, not hints. `@Stable` requires equality stability, observable notification for public property changes, and stable public property types. Violating the contract can lead to undefined optimization behavior. Do not add `@Stable` or `@Immutable` simply to force skipping.
- A type annotated `@Immutable` must actually be immutable. Public lists/collections backed by mutable data or exposed mutable references make this unsafe. Prefer immutable projections and persistent immutable collections where project dependencies and API affordances justify them; don't blanket-annotate DTOs.
- Stable parameters can be compared and composable calls skipped when equal. The key optimisation is to prevent irrelevant changes reaching the composable or to make stable values accurately represent equality.
- LazyColumn/LazyRow/LazyGrid `contentType` lets lazy layouts reuse compatible item compositions more efficiently; use a small bounded set of types, not per-item unique values. Stable unique item keys preserve scroll position when items are inserted/removed ahead of the current visible item.
- Lazy layout `cacheWindow` can trade memory and composition work for scroll smoothness. The Android docs describe a default ahead-prefetch window of 10%-50% of viewport based on observed item size; increasing cache ahead/behind has memory and CPU costs and is not inherently faster for cold launch. Do not change the app's cache window without scroll/jank traces on representative low/mid/high devices.

Sources: https://developer.android.com/reference/kotlin/androidx/compose/runtime/Stable, https://developer.android.com/reference/kotlin/androidx/compose/runtime/Immutable, https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyColumn.composable, https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListDefaults

### New Enhanced Android startup findings

Current AndroidManifest.xml explicitly declares `<profileable android:shell="true" />`, supporting production-like shell profiling while the app is non-debuggable. Keep it enabled for benchmark variants. Ensure release/profileable is not conflated with debuggable benchmark APKs.

NuvioApplication constructs the custom Coil ImageLoader lazily through `SingletonImageLoader.Factory`. The code comment explains why: Coil's auto-init ContentProvider would otherwise create the singleton before MainActivity and later custom poster fallback interceptors would not be registered. The custom loader includes crossfade, memory/disk cache, custom poster fallback, SVG, Ktor/OkHttp, Cache-Control and in-flight request dedupe, plus platform GIF/animated decoding. This is an intentional correctness constraint: do not defer the registration/factory itself beyond first image request, and do not move loader creation earlier eagerly merely to warm it. Future image work should inspect the exact image request sizes/precision, decoded bitmap dimensions, cache hit/miss ratio, fallback interceptor latency and initial hero/artwork readiness. Coil cache hits and HTTP cache semantics should be traced separately.

MainActivity currently calls `showContent()` only after its long synchronous storage/platform initialization sequence and `StartupPreloader.awaitCriticalReady()` when necessary. It sets the splash keep condition until `contentReady`, then calls `setContent`, sets `contentReady=true` immediately after composition setup, starts post-frame warmup only after one frame, and uses `ReportDrawnWhen { appReady.value }` for the fully-drawn signal. The benchmark helper separately waits for the app's explicit Home + fully-drawn state. Thus SplashScreen dismissal, first Compose frame, Android reportFullyDrawn, Home readiness, and usable Home are different milestones; preserve them as separate observables.

Application.onCreate currently calls only `DnsPlatform.initialize(this)`; ImageLoader creation is lazy. This supports deferring broad additional initialization from Application rather than adding work there. However, DnsPlatform may be a dependency for the shared OkHttp/Coil stack and must remain before any client use until callers and provider initialization are traced.

### What not to do: image-loader and lazy-list 'optimizations' without proof

- Do not remove crossfade globally; perceived image pop-in and UX regression risk. For the first above-the-fold image only, a controlled crossfade policy may be considered after measuring first render and confirming existing request-level overrides.
- Do not preload the entire initial Home image set. It can compete with startup CPU, memory bandwidth and network queueing, worsening TTFD. Measure only visible/near-visible image prefetch and cap concurrency if Perfetto/network traces show a problem.
- Do not turn up Coil memory cache/disk cache blindly; existing policy is enabled, but exact cache sizes/keys and hit rates were not retrieved in this pass.
- Do not increase LazyColumn/LazyRow cache windows just because docs expose the option; this can cause more compositions and memory retention and may not benefit startup.
- Inspect item `key` and `contentType` on expensive/repeated lazy item groups. Stable unique keys prevent state movement; low-cardinality content types may improve composition reuse. Apply only after verifying each relevant lazy scope and item types, particularly nested horizontal Home rails.

### New benchmark quality improvement candidates

Existing Enhanced startup benchmark already has explicit Home + fully-drawn readiness and compares no-compilation with packaged Baseline Profile across 10 cold iterations, plus a separate ART diagnostic method. The phone runner's strict output contract requires three instrumentation test method names for collection but only parses two timing cases, and keeps a twenty-trace invariant. Do not add diagnostics that weaken or confuse these contracts.

Additional high-value candidates after stable API validation:
1. Baseline profile evidence bundle: store source revision, APK SHA-256, profile text, profile binary hash/size, device fingerprint/API level, ART package version, profile state, compilation state, test app variant and signed-in fixture. Current note already calls out source/APK mismatch as a major failure source; make it impossible to compare a result without provenance.
2. Profile collection hardening: assert each CUJ reached its intended screen and completed; record journey failures and abort profile creation if any required journey is missing. Distinguish optional journeys only with an explicit allowlist and report.
3. Profile semantics checks: startup profile should cover first Home display path; non-startup profile should cover scroll, details and tabs. Compare rules against app DEX method references and inspect generated binary / AAB metadata after an authorized build. Do not optimize raw rule count without proof of DEX layout, hot method coverage and startup results.
4. Add launch timing distribution summary to phone runner from exact raw Macrobenchmark JSON while preserving the current parser invariants. Report median/P90 where raw results support it; Android Macrobenchmark default statistical output may need its own parser format and schema version.
5. A/B test with BaselineProfileMode.Require vs Disable using same package build, data state and same device. The current `None` case and `Require` case are correct high-level comparisons, but `None` vs Baseline Profile mixes profile use and compilation mode; add `Partial(BaselineProfileMode.Disable, warmupIterations=3)` to isolate profile contribution only after updating runner/report schema and authorizing tests/build.

### Refined priority queue

1. Do a field/call-site inventory and build an immutable HomeAddonSignals projection containing all Home-required state. Existing AddonModelsTest covers enabled, pending and error helper behavior but not the projection contract or Flow distinct behavior. Add tests first and run them before wiring production code; this is blocked by the current no-test/no-build constraint.
2. Add separately gated startup trace markers around Activity pre-setContent groups, critical preload stages and first Home composition. Use synchronous `android.os.Trace` blocks only on same-thread code and async slices for dispatcher-crossing work. Must pair begin/end in finally. Keep trace collection diagnostics out of headline timings unless its overhead is quantified.
3. Inventory Home lazy scopes for stable keys and bounded contentType, focusing on expensive poster/card components and nested horizontal rails. No lazy cache-window tuning without FrameTimingMetric/Perfetto evidence.
4. Audit Coil request precision, size resolution, hardware/software bitmap use, cache key and first-visible decode, then use traces to establish whether image decode or network is a top contributor. Keep custom fallback registration ordering intact.
5. Map the `MainActivity` initialization calls to their actual implementation work. For each, classify as object wiring, DataStore/file/database read, binder/service, native init, thread/client startup or observer binding. Defer only proven non-critical work and cover deep links, playback, PiP, notification/permission callbacks, profile/account switching and secondary process entry.

### Exa provider status

The explicitly invoked Exa search connector returned HTTP 402 because the account's credits limit was reached at 2026-11-18T04:55:59Z. No alternate search system was substituted in this pass. This is a research blocker, not evidence that no new practitioner findings exist. Resume later with separate Exa query angles for Android startup Perfetto case studies, Compose recomposition/stability, Baseline Profile/Dex layout, Coil first-screen image loading, and ContentProvider/App Startup overhead.

### Verification boundary

No Kotlin/Java/XML/Gradle/script production change was made. No tests (including shell tests), builds, APK/AAB generation, instrumentation, Macrobenchmark, phone, LADB or CI were run. This is a source and documentation research cycle only.
## Follow-up: practitioner-forum request, profile coverage gap and AndroidX plugin findings — 2026-11-18

### External community-search status

User explicitly requested forums and social media (Reddit, Stack Overflow, GitHub discussions/issues, Android developer forums). The Exa connector was invoked with five targeted searches for these communities, including Android app startup/Perfetto, Compose recomposition/stability, Baseline Profile/ART, and anecdotal failure modes. Every search returned HTTP 402: account credits limit exceeded. A follow-up page fetch for the official Nuvio PR also returned HTTP 402. Thus no new forum/social posts were retrieved or validated in this cycle. Do not attribute techniques to Reddit/Stack Overflow developers without readable post evidence. The previous note records the same service limit from the earlier pass; quota is still exhausted at 05:02-05:03 UTC.

### New confirmed coverage gap: Enhanced browse Baseline Profile

Re-fetched the exact Enhanced benchmark module sources:
- `BaselineProfileGenerator.java` blob 8db8e4f0bcb45a26046a71fbfdc289f0e1bd4207: a single `startup()` test calls `BaselineProfileRule.collect(... outputFilePrefix="startup", includeInStartupProfile=true, strictStability=true ...)` and waits for Home plus fully-drawn.
- `StartupReadiness.java` blob bc26dc8209576bbb803f1c5ab8dc50e7242df18c: reliable cold-launch readiness but currently only startup/Home markers. It exposes no browse destination/interaction helpers.
- `baselineProfile/build.gradle.kts` blob b4916e7ea4bda6994e6f81f9a3df24ac723250e1: `com.android.test`, separate full/playstore flavors, API 28+ and self-instrumenting.
- `androidApp/build.gradle.kts` blob 771db6fae6403c5451fc202dc332cd51af273a48: `baselineProfile { mergeIntoMain = false; saveInSrc = true; automaticGenerationDuringBuild = false }`; app depends on `:baselineProfile`.

Therefore Enhanced's current collection source explicitly collects the initial launcher path only. There is no browse pass in this generator. The official Mobile generator at release commit 6b7f15e uses a second `includeInStartupProfile=false` pass for Home scroll, hero details, poster details and tab navigation. This is a genuine profile coverage difference: Enhanced currently has fewer explicit non-startup journeys, not necessarily worse rules (generated profile artifacts remain uninspected).

### AndroidX Baseline Profile collection API and consumer plugin

Context7 query of AndroidX Baseline Profile plugin confirms `BaselineProfileRule.collect` supports `outputFilePrefix`, `includeInStartupProfile` and `strictStability`. The existing Enhanced call already enables strict stability. The API snippets retrieved from Context7 include current/RC references for some method overloads; do not change Java call signatures based on RC-only samples. Re-check stable 1.5.0 API exact overload before implementing additional calls.

AndroidX Baseline Profile consumer plugin docs confirm:
- `saveInSrc=true` stores generated profiles under source `src/<variant>/generated/baselineProfiles`.
- `mergeIntoMain=false` keeps profiles per variant rather than combining all variants under `src/main`.
- Automatic generation is disabled, so profile collection is manual and does not unexpectedly run during release builds.
- Plugin registers generated profile files as baseline profile source-set entries. AGP packages `baseline-prof.txt` rules as APK `assets/dexopt/baseline.prof` (the source profile file naming/variant paths need to be verified against the actual generated output).

Implication: design a second collection prefix such as `browse` or `journeys`, with `includeInStartupProfile=false`, while keeping the existing `startup` pass unchanged. The exact source output filenames and whether two collected prefixes are retained/merged by plugin and variant must be verified on an authorised profile collection before changing runner semantics. `saveInSrc`, `mergeIntoMain=false` makes a variant-specific storage plan possible, but does not by itself prove how the runner's artifact discovery handles multiple outputs.

### Runner/benchmark compatibility constraint

`scripts/nuvio-phone-benchmark.sh` currently uses `require_non_empty_startup_profile()` which searches only `*startup-prof*.txt`, and reports a startup-profile-specific failure if none is found. Do not change it to accept any arbitrary `baseline-prof*.txt` as a substitute. Its collect mode is tied to the `BaselineProfileGenerator` invocation and provenance artifacts. Add a separate validation rule for browse profile only after its filename prefix and output location are proven from an actual generated artifact. Keep the runner's strict Macrobenchmark three-method and two-timing-case contracts intact.

### Revised next best improvement: two-profile strategy with real CUJ assertions

Recommended follow-up implementation (blocked by current no-test/no-build restriction):
1. Keep `startup()` as a dedicated launcher/Home/fully-drawn pass with `includeInStartupProfile=true`.
2. Add a second `browseJourneys()` collection with `includeInStartupProfile=false`, output prefix distinct from startup, strict stability enabled, covering Home vertical scroll, horizontal rail interaction, details open/close, and tabs/Library/Search. Prioritise journeys used daily in actual app, not imitation of official paths.
3. Before each CUJ, establish correct app state and confirm each navigation target/interaction by stable test marker or resource ID. After interaction, assert that expected destination loaded and app returned to Home when needed. No catch-and-continue for required journeys; a missing UI element must fail profile collection with a useful diagnostic.
4. Validate collection success and generated text files. Verify startup rules are confined to startup path, browse rules are included only in Baseline Profile, generated binary packaging, size, DEX placement, and R8 metadata with exact app APK/source revision.
5. Extend runner to collect/copy/verify both generated artifacts under separate provenance labels, preserving startup-only requirement and existing timing JSON/Perfetto validation.
6. Benchmark the same optimized APK, with `BaselineProfileMode.Require` against a no-profile but equivalent partial compilation mode (e.g. `Partial(BaselineProfileMode.Disable, warmupIterations=3)`) to isolate profile value. Preserve current no-compilation case as a separate baseline. Do not compare `CompilationMode.None` vs profile mode as the only measure of Baseline Profile benefit because both mode and warmup/compilation state differ.

### What is and is not proven

Proven from repository source: current Enhanced has one startup-only collection call; official Mobile release generator has two passes, a startup pass and a non-startup browse pass; Enhanced consumer Gradle configuration allows manual per-variant profile outputs and keeps variants separate.

Not proven: Enhanced's final compiled profile has worse coverage or performance than official; official Mobile generated rule count/profile text/DEX layout; whether a second profile pass increases speed on target hardware; any TTID/TTFD or scroll-jank superiority.

### Community search limitation

Do not claim social/forum research as complete until Exa access is restored. Search should resume with separate targeted searches for AndroidDev Reddit field reports, Stack Overflow accepted answers, GitHub androidx/performance-samples issue discussions, and Android Developers community threads. Filter to first-hand practitioners with code, Perfetto screenshots, APK/device/API details, and measured before/after results; exclude generic advice and Android TV profiles.

### Verification boundary

No code, Gradle, runner, generator, profile artefact or benchmark change was made in this cycle. No test, shell script, Gradle task, APK build, instrumentation, device benchmark, LADB or CI was run. The note update is research-only.
## Follow-up: Android performance-samples, GitHub discussions, Compose tracing and Nuvio release-history lead — 2026-11-18

### New source discovery: Android official performance-samples repository

Inspected Android's `android/performance-samples` repository at commit `05c8130adfd2d7e35d4cd25033cb4f89b1008c1f` (MacrobenchmarkSample). This is primary-source sample code and documentation, not an informal blog.

Files inspected:
- `MacrobenchmarkSample/macrobenchmark/src/main/kotlin/com/example/macrobenchmark/baselineprofile/StartupProfileGenerator.kt` blob `45a7ca97e886c520d4d67249d8d4361b8bff51ea`: demonstrates a dedicated startup profile generator using BaselineProfileRule, 15 max iterations, 3 stable iterations and startup inclusion.
- `MacrobenchmarkSample/macrobenchmark/src/main/kotlin/com/example/macrobenchmark/benchmark/startup/StartupBenchmark.kt` blob `b8d2ee3bc83e27237e96d39124a1035c65aab253`: benchmark suite compares `CompilationMode.None`, `Partial(BaselineProfileMode.Disable, warmupIterations=3)`, `Partial(BaselineProfileMode.Require)` and `Full`, using the same startup benchmark function and StartupTimingMetric.
- `MacrobenchmarkSample/README.md`: says more than one profile file can be maintained in `src/main/baselineProfiles`; recommends separate profile generator classes per user journey, plus a separate generator for app startup. It also documents composition tracing during Macrobenchmark and shows an example report.

Key implication for Enhanced: the no-profile partial compilation case from the sample should be included as a first-class comparison. Our existing `None` vs `Require` comparison cannot isolate the incremental benefit of the packaged Baseline Profile because compilation policy differs. The appropriate three-way comparison is None, Partial Disable with 3 warmup iterations, and Partial Require; Full can remain a diagnostic/control. Make each case use identical package, signed-in fixture, launch/readiness and metric. This requires updating the phone runner's currently strict three-method contract and result schema. Do not silently extend it without tests.

### Compose composition tracing example and published measurements

Android's performance-samples README links an explicit composition tracing sample and configures Macrobenchmark with instrumentation argument `androidx.benchmark.perfettoSdkTracing.enable=true`. It states that Composition Tracing displays when Composables compose/recompose and helps identify where UI time is spent. Its sample result for `EntryRow` showed count min 5, median 6, max 6 and row time min 10.2 ms, median 11.8 ms, max 16.2 ms; corresponding custom trace measured roughly 11.7 ms median. The same sample displayed frame CPU percentiles P50 4.8 ms, P90 6.8 ms, P95 8.9 ms and P99 15.3 ms for its sample scenario. These are example-sample results, NOT Nuvio/Enhanced measurements and NOT universal Compose costs.

GitHub community discussion located in that repository:
- Issue #212, https://github.com/android/performance-samples/issues/212, requested a composition tracing sample specifically with Macrobenchmark and links to Android's tracing guidance.
- PR #263, https://github.com/android/performance-samples/pull/263, titled 'Add composition tracing example'.
These are useful maintainer-community references: they led to a concrete sample showing how to measure Compose-specific work in the same frame benchmark, rather than guessing from recomposition counts.

### Current Compose version/API caveat

Enhanced's version catalog currently pins Kotlin 2.4.10 and Compose Multiplatform 1.13.0-beta01. Context7's JetBrains Compose Multiplatform documentation search did not return the exact 1.13.0-beta01 compiler/runtime tracing integration; returned Compose Multiplatform benchmark docs target a general repository benchmark framework (SIMPLE, VSYNC_EMULATION, REAL and STARTUP) and are not a drop-in replacement for Android Macrobenchmark. Do not add Android Compose runtime tracing dependency or `ComposeToolingFlags` API based on docs that say it was added in Compose 1.11.0 unless the exact KMP artifact/version and Android target compatibility are confirmed.

Android official sample's composition tracing mechanism may require `androidx.tracing:tracing-perfetto` and `tracing-perfetto-binary` dependencies plus instrumentation setup. Confirm exact dependency versions and compatibility with current Kotlin/Compose/AGP before any modifications; not done in this cycle.

### Official Nuvio older Android navigation profile lead

Official Nuvio Mobile release metadata in `store.json`/`store-beta.json` surfaced an earlier Android performance entry: `bb1a5ffa perf(android): add root navigation baseline profile` dated in the release history around 2026-09-11. This is an additional upstream historical implementation worth investigating. A short-SHA file lookup and the available PR/issue search did not retrieve the exact commit source or confirm the PR number/body; issue 1888 was unrelated and must not be confused with this commit. Treat only the release-history entry and commit hash as verified, not its contents or claimed effect.

Current official Nuvio source comparisons already completed remain valid: the later generator at commit `6b7f15e` uses a dedicated startup pass plus a separate non-startup Home scroll/details/tabs journey pass, with individual journeys catch-and-continue. Generated official mobile profile artifacts remain unavailable.

### Current profile collection strategy recommended for Enhanced

1. Keep Enhanced's existing dedicated `startup()` profile method and `startup` prefix; it waits for Home + fully-drawn and includes rules in Startup Profile.
2. Add a dedicated browse-profile generator or second explicit collection pass (after exact AndroidX Benchmark 1.5.0 stable API verification) for Home scroll, poster/hero details, search, library and tab journeys, with startup inclusion false and strict stability true.
3. Use separate generator classes or methods per journey rather than one large opaque block. Each journey must navigate to a deterministic target and assert the actual destination. Required journey failure must fail collection; no catch-and-continue.
4. Verify output file naming and plugin handling of multiple profile files from a real generated result; keep `startup-prof*.txt` as a required artifact and add separately labelled browse profile validation. Existing Gradle consumer uses `mergeIntoMain=false`, `saveInSrc=true`, `automaticGenerationDuringBuild=false`.
5. Benchmark with None, Partial Disable + 3 warmups, Partial Require. Optionally Full as a separate reference. All must use identical signed-in state and Home/fully-drawn readiness.
6. For Compose diagnosis, use a separate profiling run with Perfetto SDK tracing and the exact supported dependency/argument combo for the pinned stack. Keep tracing-enabled numbers out of clean TTID/TTFD and frame-jank headlines unless overhead is quantified.

### Coil 3.6.3 version-specific image loading discovery

Context7 resolved Coil 3.6.3 exactly, matching the version catalog. Its documentation highlights a potentially important first-screen optimisation check: when using `rememberAsyncImagePainter`, apply `rememberConstraintsSizeResolver()` both to the ImageRequest `.size(sizeResolver)` and to the Image modifier. This allows Coil to load at on-screen dimensions rather than the original image resolution. Enhanced uses an ImageLoader factory and Compose image usage must be audited independently to see whether current `AsyncImage` already handles constraints automatically or whether any custom painter path accidentally decodes original-size assets.

Coil 3.6.3 documentation also shows disk-only preloading using `BlackholeDecoder.Factory()` with memory cache disabled, avoiding decode and memory-cache work when the goal is only to populate disk cache. This is a conditional tool for measured prefetch; it is NOT a general recommendation to preload every poster. For Nuvio, use only when traces show a repeat imminent request and network latency dominates; don't compete with first-frame decode/network resources.

Sources: https://github.com/coil-kt/coil/blob/3.6.3/coil-compose/README.md, https://github.com/coil-kt/coil/blob/3.6.3/docs/faq.md, https://github.com/coil-kt/coil/blob/3.6.3/docs/image_loaders.md

### Forum/social/Twitter research status

Exa was explicitly called with five distinct query workstreams covering broad Android practitioner cases, Reddit r/androiddev, Stack Overflow, GitHub Android performance discussions, and Twitter/X public posts. Every request returned HTTP 402 (account credits limit exceeded); timestamp at latest retry was 2026-11-18T05:10:18Z. Therefore no new Reddit, Stack Overflow or X/Twitter results were retrieved. GitHub's connected search did return the Android performance-samples discussion above. Do not claim a social-media sweep completed. Resume X/Twitter and community work when Exa access is restored; prioritize posts by Android performance engineers with Perfetto screenshots, APK/device/API metadata and reproducible before/after values.

### Refined concrete implementation priorities

1. Before broad source changes, introduce distinct startup/browse profile generation and assertions, and expand the benchmark comparison modes to isolate profile contribution. This closes the verified profile coverage gap against official Nuvio and follows Google's own sample architecture.
2. Update the phone runner contract only alongside regression tests. Required artifacts should include startup and browse profile text, three distinct compilation comparisons, clean benchmark JSON and traces; maintain exact APK/test APK provenance and existing output invariants.
3. Add a separate Compose profiling mode with composition trace output. Inspect the first Home screen and nested poster rows for time-consuming composables rather than merely counting recompositions.
4. Audit Coil 3.6.3 usage in every image rendering helper, especially custom `rememberAsyncImagePainter` sites, request `SizeResolver`, original dimensions and decode results. Do not touch `AsyncImage` call sites already using automatic constraint-aware sizing without evidence.
5. Continue the root Activity/preloader phase trace audit and selectively collect state projections after test coverage is available.

### Verification boundary

No Kotlin, Java, Kotlin DSL, manifest, dependency, runner or profile generation code was changed in this pass. No Gradle/test/shell command, APK/AAB, instrumentation, Macrobenchmark, LADB/device or CI was run. No Reddit, Stack Overflow or X/Twitter pages were fetched due Exa quota. The only intended repository modification is this research note.

## 19 November 2026 — Cycles 59–60: isolate profile benefit and validate benchmark provenance

### Cycle 59 — add a profile-disabled partial-compilation control

- The live `main-build-testing` checkpoint was `8b5547dd9a60f53be50df640bdd2d38fc62c61ed`. The two-arm benchmark compared `CompilationMode.None` to `Partial(BaselineProfileMode.Require)`; that is useful as a worst-case vs packaged-profile comparison, but it does not isolate the incremental effect of the Baseline Profile from the effect of partial compilation and warmup.
- Added `startupPartialWithoutBaselineProfile()` using `new CompilationMode.Partial(BaselineProfileMode.Disable, 3)`. It shares the exact existing launch, Home/fully-drawn readiness, `StartupTimingMetric`, `ArtMetric`, cold-start mode and ten measured iterations. No app-runtime behavior or product code changed.
- Updated the phone runner's per-test, JSON, metrics and Perfetto contracts from two to three cases (30 unique per-iteration traces). Its report now separates: partial/no-profile vs no compilation; incremental profile vs partial/no-profile; and total profile vs no compilation, for TTID and TTFD. ART JIT, verification and optional class-load data are reported for all three arms.
- Updated the matching source-contract and phone-runner fixture checks; the in-memory JSON fixture now has three arms, ten samples and ten distinct Perfetto references per arm. The fixture is deliberately synthetic and is not a performance result.
- Evidence: Android's Macrobenchmark guide recommends comparing enabled and disabled Baseline Profile compilation. The current official Android performance sample also demonstrates `None`, `Partial(Disable, warmup=3)`, `Partial(Require)` and `Full` as distinct comparison states. The repository pins Macrobenchmark 1.5.0; Context7 confirmed the Java `Partial(BaselineProfileMode, int warmupIterations)` constructor.
- The Exa connector returned research results successfully during this session, so its previously recorded quota failure is no longer the current access state. This does not mean an exhaustive social-media sweep was completed; the useful evidence retrieved here was Android's official guide, API reference and performance sample.

### Cycle 60 — reject JSON that misstates warmup configuration

- Extended the phone runner's summary parser to require `warmupIterations=0` for `startupNoCompilation`, `warmupIterations=3` for `startupPartialWithoutBaselineProfile`, and `warmupIterations=0` for `startupWithBaselineProfile`. A JSON artifact that contains the right case names but reports a different warmup policy now fails before the summary can be accepted.
- Added a dedicated `wrong-warmup-iterations` fake JSON fixture and a static contract check for the exported-field validation. This protects the comparison from a future benchmark method/runner drift; it does not claim AndroidX has executed those modes on a device.
- No speculative change was made to Home scroll detection: UI Automator 2.4.1 documents `UiObject2.scroll()` as returning whether the object can still scroll in the requested direction after the gesture, so `false` at a boundary is not proof of a failed gesture.

### Review and verification boundary

- Both cycles were integrated as ordinary fast-forward commits on `main-build-testing`, using an expected-head lease; the branch head was fetched again after each update.
- Static source checks and an in-memory parse of the synthetic three-arm JSON fixture were performed during authoring. The repository's shell regression scripts were **not executed**: this session had no usable local checkout (the container could not resolve GitHub), and no remote test workflow was triggered.
- No Gradle task, Java/Kotlin compilation, APK/AAB build, profile generation, instrumentation, Macrobenchmark, CI, LADB or physical-device measurement was run. This was intentional under the no-build/no-CI instruction. Therefore no real Nuvio startup improvement or numerical speedup is claimed.

Sources:
- [Android Developers — Benchmark Baseline Profiles with Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)
- [AndroidX CompilationMode.Partial API](https://developer.android.com/reference/androidx/benchmark/macro/CompilationMode.Partial)
- [AndroidX BaselineProfileMode API](https://developer.android.com/reference/androidx/benchmark/macro/BaselineProfileMode)
- [Android performance-samples — MacrobenchmarkSample](https://github.com/android/performance-samples/tree/main/MacrobenchmarkSample)
- [AndroidX Macrobenchmark API source](https://android.googlesource.com/platform/frameworks/support/blob/androidx-main/benchmark/benchmark-macro/api/restricted_current.txt)


### Cycle 61 — make thermal state part of phone benchmark eligibility

The phone runner already saved `dumpsys thermalservice` output in the pre/post environment snapshots, but no result-integrity check interpreted it. This left a material gap: the report asked users to inspect possible thermal throttling, yet did not identify a known moderate-or-higher thermal state itself.

- Added a POSIX/AWK parser for the exact numeric `Thermal Status` field in the existing snapshot. No extra device command, polling loop, sensor read, or timed-workload instrumentation was added.
- A pre-run status of 2–6 now blocks the measurement before `am instrument` starts. Status 0 (NONE) and 1 (LIGHT) remain eligible; Android's guidance says light status does not impact UX, while moderate status affects foreground activities.
- Missing, unreadable or vendor-formatted diagnostics are recorded as `unknown` and do not get silently treated as a clean status.
- After the run, a status of 2–6 writes `VERDICT: THERMALLY CONTAMINATED`. The JSON and traces remain available for diagnosis, but the summary explicitly says not to use the timings as an unthrottled comparison. The new `thermal-integrity.txt` is also referenced from the copied-files manifest.
- Where both snapshots are 0–1, the result says only that no moderate-or-higher status was observed at those two capture points. It does not claim that transient status changes could not occur between them.

Regression fixtures cover pre-run moderate rejection, light-only acceptance, a post-run rise to moderate, and unavailable thermal diagnostics. The static metric-contract test protects the snapshot parser, preflight gate, qualification text and report integration.

### Cycle 62 — expose the actual Macrobenchmark test-arm order

The summary displayed the three comparison arms in a fixed explanatory order, but did not show the order in which AndroidJUnitRunner actually executed the JUnit test methods. That order is relevant context when reviewing results from a multi-arm run.

- The phone summary now derives the first-observed sequence from the three recognized `INSTRUMENTATION_STATUS: test=` records in the saved instrumentation log.
- It de-duplicates repeated status records for the same method, requires all three expected comparison cases, and records the observed sequence rather than assuming a particular ordering.
- Added a reverse-order fixture. It verifies that a profile/partial/no-compilation execution sequence is preserved in the report as-is, not rewritten to the display order.

### Verification boundary for cycles 61–62

- Re-fetched the remote runner and regression sources after each change. The isolated POSIX shell/AWK parser, verdict writer and execution-order collector passed local fixtures for status values 0, 1, 2 and 6, unknown/missing diagnostics, contaminated/unknown verdicts and reversed method order.
- The full `test-nuvio-phone-benchmark.sh` and `test-android-startup-metric-contract.sh` repository scripts were **not run**. There is no local checkout available in this session, and no remote workflow was triggered.
- No Gradle task, Java/Kotlin compilation, APK build, profile generation, instrumentation, Macrobenchmark, LADB or physical-device measurement was run. No numerical performance improvement is claimed.
- No workflow, app-runtime, Kotlin/Java benchmark API, dependencies, variants, package identity or CI trigger was changed.

Research source: [AOSP — Thermal mitigation and thermal status codes](https://source.android.com/docs/core/power/thermal-mitigation), last updated 9 October 2026. Its severity table defines NONE as no throttling, LIGHT as throttling without UX impact, MODERATE as foreground-activity impact, and the higher states as increasingly severe. The benchmark continues to rely on AndroidX Macrobenchmark's existing JSON and Perfetto outputs; see [Android Developers — Write a Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).


### Cycle 63 — Persist APK source identity across LADB commands

- Fixed the process-local provenance gap: install actions now persist the exact app and test APK basenames and SHA-256 values to `current-install-provenance.txt` alongside the existing action/flavor marker.
- Profile collection and measurement load the persisted record in their own later shell process, verify the saved action/flavor, verify the expected variant-specific filenames, and reject unavailable or malformed hashes.
- The combined `run` command deliberately validates the NonMinifiedRelease source pair during its profile phase, then validates the BenchmarkRelease pair after installing it for measurement.

### Cycle 64 — Verify installed app and instrumentation APK bytes before measurement

- Query AOSP package-manager code paths for both `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile` using `pm path` before instrumentation.
- Require exactly one APK path for each package because this benchmark bundle supplies standalone APKs. No path or multiple paths blocks the run; a readable installed APK whose SHA-256 differs from the saved source also blocks the run.
- If Android returns a path but the shell cannot read the file, provenance records `UNVERIFIED` rather than claiming a byte match. This preserves compatibility with OEM builds that restrict shell access.
- Added fixtures for source identity persistence, both stale-package cases, unreadable APK paths, missing path output and split APK output. Static checks enforce verification before `am instrument`.

### Verification boundary for cycles 63–64

- An isolated POSIX shell helper fixture passed cases for exact-byte match, stale-byte rejection, unreadable path, missing path and multiple-path rejection.
- The full `test-nuvio-phone-benchmark.sh` and `test-android-startup-metric-contract.sh` scripts were not executed. No local checkout was available; only the current remote sources were inspected and updated.
- No Gradle task, build, CI, profile generation, instrumentation, LADB/device action or real startup measurement was run. No startup-speedup claim is made.
- Scope is limited to the phone runner, its regression/static-contract fixtures and benchmark notes/spec/plan. No production app code, benchmark Java code, dependency, build configuration or workflow was changed.

Research source: [AOSP PackageManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) — `pm path` emits the base `sourceDir` and each `splitSourceDirs` path with a `package:` prefix. The shell-readable status of those files is not treated as guaranteed; inaccessible bytes remain `UNVERIFIED`.

### Cycle 65 — resolve one exact self-instrumenting AndroidJUnitRunner

- The previous resolver filtered pm list instrumentation to the self-instrumenting test package but then selected the first matching component with head -n 1. It accepted a wrong runner class and silently chose one candidate when metadata was ambiguous.
- The runner now requires exactly one matching instrumentation component and requires its exact component name to be com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner. Missing, wrong-class and multiple-candidate states fail before am instrument.
- The fake package-manager fixture now models missing, wrong and ambiguous instrumentation metadata. Each regression asserts that the command fails before instrumentation is launched.

### Cycle 66 — validate each clean-run profiler output as a typed iteration trace

- AndroidX Macrobenchmark combines the measured-iteration Perfetto trace paths with any additional profiler result files in profilerOutputs. The old phone contract counted Perfetto type fields, filenames and iteration labels independently; it did not count the output objects or prove that each iteration label belonged to a PerfettoTrace object with its own filename.
- The validator now requires exactly ten profiler output objects per comparison arm, ten PerfettoTrace entries and ten filenames. For every Trace Iteration N label, it validates the same JSON object contains exactly one PerfettoTrace type and one filename.
- Additional profiler artifacts are rejected with a specific clean-run diagnostic. This preserves the existing unprofiled timing contract; it does not enable method tracing or add profiler overhead to the benchmark.
- Added an extra-profiler-output fixture that appends a MethodTrace result and asserts the explicit rejection. The static contract also protects exact runner identity and the profiler-output rule.

### Verification boundary for cycles 65–66

- Isolated POSIX shell checks reproduced the old instrumentation resolver accepting a wrong runner and selecting the first of two candidates; the new resolver rejected missing, wrong and ambiguous metadata and accepted the unique expected AndroidJUnitRunner.
- An isolated AWK fixture accepted ten correctly typed iteration traces and rejected an additional MethodTrace profiler object.
- The full test-nuvio-phone-benchmark.sh and test-android-startup-metric-contract.sh scripts were not run. The container still cannot resolve GitHub, so there is no local repository checkout; no remote workflow was started.
- No Gradle task, Kotlin/Java compilation, APK build, profile generation, instrumentation, Macrobenchmark, LADB session, device benchmark or CI was run. No Nuvio startup speedup is claimed.

Research: [AndroidX Macrobenchmark source — profiler output merging](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt) shows that measured iteration traces and additional profiler results are combined into profilerOutputs. [Android Developers — Benchmark in Continuous Integration](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci) documents per-iteration Perfetto traces and the presence of profiling trace files in the output directory.

### Cycle 67 — fail closed when API 33+ startup output omits TTFD

- AndroidX Macrobenchmark 1.5.0 StartupTimingMetric emits timeToFullDisplayMs only when its trace query finds a fully-drawn event. The metric documentation says TTFD may be unavailable before API 29; the phone runner already refuses API levels below 33.
- The phone JSON validator now requires the exported Android SDK value to be numeric and at least 33, and requires exactly three timeToFullDisplayMs metric objects for the three startup comparison arms. A missing fully-drawn measurement can no longer be confused with a valid TTID-only startup report.
- Added fixtures for a missing single-arm TTFD metric and a shortened TTFD raw-sample list. The existing per-metric sample validation enforces ten positive samples and recomputes reported statistics against the raw runs.
- No benchmark method, startup readiness signal, Android app code, dependency, metric, compilation mode or iteration count changed.

Verification and limits: the code and fixtures are committed remotely, but the repository regression scripts were not run because no local checkout is available and no CI/build was authorised. The guard has been inspected in the live source; this is not a claim that the full test script passes. No instrumentation or device measurement was performed.

Research: [AndroidX Benchmark 1.5.0 StartupTimingMetric API](https://developer.android.com/reference/androidx/benchmark/macro/StartupTimingMetric) documents TTFD as dependent on Activity.reportFullyDrawn and potentially unavailable before API 29. [AndroidX Macrobenchmark Metric.kt](https://android.googlesource.com/platform/frameworks/support/+/225853c2fd3977d06ea9c0efaddd35ae1d81e3a6/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Metric.kt) shows that timeToFullDisplayMs is omitted from the result map when the trace query has no full-display timestamp.

### Cycle 68 — validate Android SDK from context.build.version.sdk

- Cycle 67 introduced API 33+ and three-arm TTFD guards, but the SDK was extracted by searching the entire JSON document for the first "sdk" key. That was broader than AndroidX BenchmarkData's actual schema and could accept an unrelated value.
- The runner now extracts sdk from the nested build.version object under context. Missing, malformed or pre-33 SDK metadata fails, and the error says that top-level or unrelated sdk fields are ignored.
- This is a result-context integrity fix. It does not change the AndroidX version, Macrobenchmark measurements or user workload.

### Cycle 69 — protect exact SDK metadata extraction with malformed fixtures

- Added a missing-context-sdk fixture and a misleading-global-sdk fixture. The latter places a plausible top-level sdk=35 next to a missing context.build.version.sdk so the old global lookup would have accepted it.
- Both cases are included in the invalid JSON fixture loop and assert the specific nested-context failure diagnostic.
- The static contract protects the exact version-object extraction as well as the pre-existing API 33+ and TTFD completeness requirements.

### Verification boundary for cycles 68–69

- Re-fetched the live runner, phone regression fixture, static contract and both engineering notes after the changes. Source inspection confirmed all new fixture identifiers are in the executed bad-JSON loop, and the static contract checks the nested SDK extraction and TTFD guard.
- The full repository shell scripts were not run because no local checkout is available. The connected GitHub tool can edit/read repository files but cannot execute their shell fixtures. No remote workflow was triggered.
- No builds, CI, Gradle tasks, instrumentation, profile generation, LADB or physical-device benchmark was run. The malformed cases are fixtures, not measured data. No startup-speed gain is claimed.

Research: [AndroidX BenchmarkData.kt](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt) defines SDK as context.build.version.sdk. [Android Developers — Benchmark in CI](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci) documents the JSON context structure.

## 19 November 2026 — Benchmark cycles 70–72: power-state and result-file integrity

### Cycle 70 — repair stale SDK and shell static contracts

- Removed a stale static assertion that still expected the old global SDK lookup after Cycle 68 changed extraction to `context.build.version.sdk`.
- Preserved the nested SDK, API 33+ and TTFD completeness checks.
- Moved the static-contract PASS line after all assertions, so a later failure cannot follow an already printed success message.
- Corrected invalid `fi    case` adjacency in the fake-device test harness. The missing command separator caused POSIX shell syntax rejection; the exact fragment was verified locally in both failing and corrected forms. The full repository test script remains unexecuted.

### Cycle 71 — reject and report Battery Saver contamination

- The phone runner already captured `settings get global low_power` in its pre/post environment snapshots but did not interpret it.
- AOSP defines the LOW_POWER mode as intended to save battery at the cost of performance. A pre-run value of `1` now blocks Macrobenchmark before `am instrument`; a post-run value of `1` marks the saved result `BATTERY-SAVER CONTAMINATED`.
- Missing/unparseable state is `unknown`, not off. The generated integrity report is appended to `benchmark-summary.txt` and listed in `copied-files.txt`.
- Added fake-device coverage for preflight rejection and a post-run state change. No extra device command or polling was introduced.

### Cycle 72 — require one result JSON per instrumentation invocation

- The previous code counted benchmark cases across every copied JSON file but wrote the human-readable summary from only the first file.
- The runner now requires exactly one new `benchmarkData.json` artifact for this single self-instrumenting benchmark module. Multiple files fail with an explicit count instead of silently discarding the others.
- Added a fake-device fixture that duplicates the valid result JSON and checks the multiple-file diagnostic. The 30 unique per-iteration Perfetto trace requirement is unchanged.

### Verification and limits for cycles 70–72

- Re-fetched all changed runner/test/note files from GitHub and inspected the wiring of the new fixtures and static assertions.
- Ran only an isolated AWK parser check for Battery Saver values `0`, `1` and `unavailable`; this produced `0`, `1` and `unknown`. This was not the repository regression script.
- Neither repository shell regression script was executed because there is no local checkout and the GitHub file integration cannot execute shell scripts. The changes have not been represented as test-suite passes.
- No builds, CI, Gradle tasks, instrumentation, profile collection, LADB commands or physical-device measurements were run.

Sources: [AOSP Power HAL Mode.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/power/aidl/android/hardware/power/Mode.aidl) · [Android Developers — Test power-related issues](https://developer.android.com/topic/performance/power/test-power) · [Android Developers — Benchmark in Continuous Integration](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci) · [AndroidX Macrobenchmark source](https://android.googlesource.com/platform/frameworks/support/+/0c2ddcd70f6090ccc660e49c03048e1de85c2073/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt)

## Android startup benchmark — exact context JSON provenance (Cycles 73–74)

### Cycle 73 — make SDK eligibility depend on the real root context

- Reproduced a false acceptance in the old validator: a root-level build.version.sdk=35 could qualify a report even when the actual AndroidX context object was empty.
- Added a quote/escape-aware AWK direct-object extractor and changed SDK eligibility to follow only context.build.version.sdk.
- Added fake-device cases for a root-level build decoy and non-object context, build and version values. Existing missing-SDK and unrelated-SDK fixtures remain.

### Cycle 74 — keep summary metadata tied to the same AndroidX context

- Replaced global first-key lookups for model, SDK, CPU, memory, ART Mainline, sustained-performance mode and OS codename with exact context/build/version object lookups.
- Added a valid JSON fixture with root-level metadata decoys and a string containing escaped quotes and a brace. Regression assertions require context-owned values and reject root-level decoys.
- Extended the static contract to check every context-owned metadata field and ensure the old global summary lookups do not return.

### Verification and limits for cycles 73–74

- Reproduced the original root-build false acceptance with the previous AWK logic.
- Ran isolated AWK fixtures for valid/nested/decoy JSON, escaped quote and brace handling, and missing/non-object object-chain members. The direct-path assertions passed.
- Independently validated the new fake JSON fixture output as syntactically valid JSON.
- Re-fetched and source-inspected the remote runner, fake-device fixture and static-contract script. The full repository test scripts were not run because no local checkout is available through this session.
- No build, CI, Gradle task, instrumentation, profile generation, LADB or physical-device measurement was started. No runtime-performance result or speedup is claimed.

Sources: [AndroidX BenchmarkData](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt) · [Android Developers — Benchmark in Continuous Integration](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci) · [AndroidX Macrobenchmark](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt)

## Android startup benchmark — direct JSON member integrity (19 November 2026)

### Cycle 75 — read only direct JSON members and reject duplicate keys

The context fix in Cycles 73–74 still left a narrower parsing flaw: the scalar readers used substring searches inside the selected object. A nested `metadata.cpuCoreCount` could still be mistaken for the direct `context.cpuCoreCount`, and a duplicate direct key would silently use the first value. This affected both device labels and metric integrity.

Added a quote/escape-aware `json_member_value(source, field)` to the two AWK programs in the phone runner. It reads only direct members at the current object depth, skips object/array descendants, handles braces and escaped quotes inside string values, and returns unavailable for missing or repeated direct members. Numeric, string and boolean readers, object extraction and raw `runs` array extraction now use this one rule.

The fake-phone cases now include nested context metadata, duplicate root `context`, nested metric fields and duplicate direct metric fields. A nested-context success case proves direct AndroidX values remain visible while nested values are reported as unavailable rather than substituted.

### Cycle 76 — keep all metric call sites compatible with exact object parsing

Switching scalar reads to direct-member semantics exposed a caller mismatch: some regex matches still included the property-name prefix (for example, `"artJitCount":`) instead of passing the JSON object beginning at `{`. The TTID/TTFD, ART validation, ART summary and class-loading summary paths have now all been adapted to pass the actual metric object. The class-loading paths also check that their metric objects were found before reading medians.

Added an invalid fake report with ART metric values nested under a child object, and extended the static contract to reject old prefixed metric-object handoffs. Existing fake-report assertions still require non-zero ART and class-loading summaries, so the stricter parser cannot silently turn valid values into zeros without the fixture detecting it.

### Verification boundary for cycles 75–76

- Ran isolated POSIX AWK checks using the same direct-member helper implementation: valid context values; ignored root/nested decoys; duplicate direct fields; escaped quotes and braces; nested arrays/objects; direct numeric/boolean/string reads; raw metric samples; nested-only metric values; and duplicate metric fields. All focused assertions returned the expected values or unavailable result.
- Re-fetched the live runner, phone-runner fixtures and static contract after the edits. Audited every metric reader call site, including ART validation and ART class-loading summary handoffs; the previous prefixed-object call sites were corrected.
- Confirmed that nested-context, nested-startup-metric, nested-ART-metric, duplicate-context and duplicate-metric fixtures are included in the fake-phone invalid or summary test paths, and that existing summary assertions check the expected ART/class-loading values.
- The complete repository scripts `scripts/tests/test-nuvio-phone-benchmark.sh` and `scripts/tests/test-android-startup-metric-contract.sh` were **not run**: the session has no local repository checkout and the GitHub file integration cannot execute shell scripts. Source/fixture wiring and isolated AWK behavior are verified; a complete regression-suite pass is not claimed.
- No Gradle task, build, CI workflow, instrumentation, Baseline Profile collection, LADB action or physical-device benchmark was run. The app, Java benchmark workload, comparison arms, metrics, iterations, profiles, APK variants and dependencies are unchanged. These cycles improve report correctness only; no startup-speed improvement is claimed.

Evidence: [AndroidX BenchmarkData JSON model](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/json/BenchmarkData.kt) defines device context and metric result structures; [Android Developers — Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) documents the 1.5.0 release and benchmark JSON context additions; [AndroidX Macrobenchmark implementation](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt) writes profiler outputs and iteration traces.

## Android startup benchmark — isolated heap allocation diagnostics (Cycles 77–78, 19 November 2026)

- Added a separate `StartupMemoryBenchmark` using AndroidX Benchmark 1.6.0-alpha02 `MemoryProfilingConfig(true, true)` for ART and native allocations. It uses one cold Baseline Profile-required Home launch and the existing fully-drawn readiness gate; AndroidX adds a separate memory profiling pass after the unprofiled measurement iteration.
- Split the macrobenchmark instrumentation-library version from the Baseline Profile Gradle plugin: Macrobenchmark alone uses 1.6.0-alpha02; the Gradle plugin remains stable 1.5.0.
- Added a dedicated `memory-profile full|playstore` phone action and separate summary/result directory. It uses existing API33+, installed-APK provenance, thermal and Battery Saver gates, and filters instrumentation to the new diagnostic test only.
- The validator expects exactly one JSON result, one standard measurement trace label and one `Memory Profiling Iteration 0` output, verifies the PerfettoTrace type and `-memoryProfiling.perfetto-trace` suffix, then requires one copied non-empty trace. The clean three-arm/30-trace `measure`/ `run` workflow remains separate.
- The diagnostic summary reports the exact AndroidX heapprofd settings (ART `com.android.art`, native `libc.malloc`, 2,048-byte sampling interval, 500 ms continuous dumps, 8 MiB shared buffer) and warns that sampled call stacks are not an exact heap ledger, RSS/PSS or retention graph. It documents Perfetto UI / Android Performance Analyzer and `traceconv`.
- Verified the existing app main manifest declares `<profileable android:shell="true" />`, and the Full and Play Store overlays do not remove it. Added static regression coverage for that requirement because Perfetto requires profileable or debuggable target apps on user builds.

**Verification limits:** Focused isolated AWK/shell checks passed for valid memory output and eight invalid variants. The repository phone-runner and static-contract scripts were not executed, Java was not compiled, and the merged APK manifest was not built/inspected. No build, CI, instrumentation, LADB or physical-device run was started. A new benchmarkRelease test APK containing `StartupMemoryBenchmark` is required before the opt-in action can run. No startup-speed or memory-reduction claim is made.

Sources: [AndroidX 1.6 API source](https://github.com/androidx/androidx/blob/ab755341f6b7c8bd7126458878b2a8de9c02bdd1/benchmark/benchmark-common/src/main/java/androidx/benchmark/ExperimentalBenchmarkConfigApi.kt) · [Macrobenchmark additional memory pass](https://github.com/androidx/androidx/blob/ab755341f6b7c8bd7126458878b2a8de9c02bdd1/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt) · [AndroidX heapprofd sampling configuration](https://github.com/androidx/androidx/blob/ab755341f6b7c8bd7126458878b2a8de9c02bdd1/benchmark/benchmark-common/src/main/java/androidx/benchmark/perfetto/PerfettoConfig.kt) · [Perfetto heapprofd](https://perfetto.dev/docs/data-sources/native-heap-profiler)

### Follow-on result-identity and app-profileability checks

- Extended the dedicated memory-result validator to require the exact JSON `className` and `repeatIterations=1`, not just a matching test name. Fake-device invalid modes now include wrong class and unexpected iteration count.
- Static contract coverage now also checks that the Full and Play Store manifest overlays do not remove the main manifest's shell-profileable entry.
- The complete phone-runner and static-contract scripts remain unrun; the additional modes were checked only through isolated AWK/shell examples and source inspection.

- The generated summary also records AndroidX's `block_client=true` behavior (allocation completeness/determinism is prioritized over frame timing) and Perfetto's startup-coverage caveat: some of the earliest Zygote-specialization allocations may occur before heapprofd attaches. These are interpretation constraints, not measured Nuvio outcomes.


## Follow-on benchmark analysis improvements — 19 November 2026

### Bootstrap intervals (Cycle 79)

- Added 10,000 deterministic independent-arm bootstrap resamples for the median differences in TTID and TTFD. The report includes the incremental Baseline Profile effect versus partial compilation without the profile, and the total effect versus no compilation.
- The fixed-seed Park–Miller generator avoids relying on AWK-specific rand() sequences, keeping the result repeatable across target POSIX awk implementations.
- The new interval is exploratory, not a causal significance test. The three JUnit arms remain grouped rather than interleaved; the report explicitly states that the interval does not remove thermal, time or test-order bias. The existing ten measured launches per arm and all timing workloads remain unchanged.
- The engineering trigger was Bluesky PR #12092, which reports 45 interleaved cold starts per configuration and bootstrap intervals; the PR's Pixel 7a results are not assumed to apply to Nuvio.

### Profile artifact audit (Cycle 80)

- After the Startup Profile and Home-browse Baseline Profile pass their existing non-empty checks, the phone runner now emits profile-audit-summary.txt.
- The audit records each original profile's SHA-256, nonblank and unique exact-rule counts, duplicate count, method/class-or-other candidate counts and H/S/P flag counts.
- This is inventory-only. It does not rewrite, deduplicate, normalize, reorder or reject generated profile rules.

### Verification

Focused POSIX awk checks passed for the deterministic bootstrap calculation and for the read-only profile inventory with synthetic duplicate rules. The updated fake-device test fixtures assert the new summary output and duplicate counts; the static contract checks the seed/resample count, order-bias warning and audit placement. The complete phone-runner and static-contract scripts were not run because no local checkout is available. No build, CI, compilation, instrumentation, LADB action or device benchmark was run. No app startup improvement or outperformance claim is established by these reporting changes.

Research sources: [Bluesky Android startup benchmark PR #12092](https://github.com/bluesky-social/social-app/pull/12092) · [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) · [AndroidJUnitRunner test filtering source](https://github.com/android/android-test/blob/main/runner/android_junit_runner/java/androidx/test/filters/TestsRegExFilter.java)


## 22 November 2026 — profile-flush and instrumentation evidence follow-up

### Exact phone evidence reviewed

- The 02:07 `playstore` run's `instrumentation(5).log` records 14 successful `startup` readiness outcomes, then fails in AndroidX `CompilationMode.Partial.compileImpl` with: `Process com.nuvio.media.benchmark never flushed profiles in any process`. The subsequent `homeBrowseJourneys` test starts and records seven readiness outcomes, but the uploaded log ends without a complete final result for that test.
- The matching provenance records runner SHA-256 `d585e7d075739cc7cc0a689a07778acc430441ff1aff7c6e42cca74752cede2e`. The installed app and test APK hashes match their recorded source APK hashes. This proves byte identity with the supplied APK files, but the provenance does not identify the source commit or prove those APKs contain the latest branch changes.
- The 02:08 `full` run's `instrumentation(6).log` records six successful startup readiness outcomes before a 16.7-second active-window wait ends with `com.draco.ladb` owning the active window. Its Home-browse path then reports the old `StaleObjectException` at `StartupReadiness.targetMarker` / `HomeBrowseReadiness.markerHasVisibleBounds`.
- The 02:09 `instrumentation(7).log` contains only the initial test-start records. It is incomplete and cannot establish a pass or a final failure cause.

### Corrections made in this follow-up

- Retained the installed `ProfileInstallReceiver` / package-metadata check from PR #15, but removed its standalone `ACTION_SAVE_PROFILE` broadcast and result-code-12 hard gate. The preflight could only test that separate broadcast at that earlier instant; it could not prove AndroidX would discover the target process during its later collection flush.
- Added a time-stamped target-process timeline using `ps -A` sampling during the actual BaselineProfile instrumentation, plus a live filtered AndroidX `Benchmark`, `ProfileInstaller` and `AndroidRuntime` logcat capture. These files are retained in the run directory and named in the failure diagnostic. The periodic process sample is supporting evidence, not proof of process state between samples; AndroidX's own logcat around its flush is the key correlation.
- Kept the strict foreground check. The LADB/System UI interruption is an environment failure; no timeout was increased and no readiness requirement was weakened.
- Kept the current `StaleNodeRetry` implementation. The current `StartupReadiness` reacquires nodes and retries stale read operations, including visible-bounds reads; `HomeBrowseReadiness` routes its target-marker bounds check through that helper. The top-level phone regression suite now invokes the focused stale-node retry test and checks this integration.
- No playback, production UI, or app navigation code was changed.

### AndroidX compatibility findings

The repository pins Macrobenchmark `1.6.0-alpha02`, ProfileInstaller `1.4.1`, and UI Automator `2.4.1`. AndroidX Benchmark release notes state that 1.6.0-alpha01 fixed Baseline Profile capture for processes whose names do not use a colon pattern and fixed a virtual-file-flush issue. The 1.6.0-alpha02 release notes do not list another process-discovery correction. The app's main and flavor manifests declare no `android:process` or `isolatedProcess` components, so there is no manifest evidence of a secondary app process that would justify a speculative ProfileInstaller version change.

AndroidX's collection implementation discovers the target package's running processes at the actual flush point, then signals those processes to save their ART profiles. A zero-process discovery result is distinct from a receiver's standalone response code. Therefore the observed flush failure is not proven fixed by removing PR #15's preflight gate; the new in-run process timeline and AndroidX logcat are intended to identify whether the package process is visible at the flush instant and what result the per-process save operation returns.

### Verification boundary

- The updated source and regression fixtures were re-fetched from the new fix branch and checked for the receiver metadata gate, absence of the premature broadcast, monitor placement around instrumentation, process/logcat artifact names, and stale-node test integration.
- The repository shell regression suite was not executed in this session because the connected GitHub file integration can read and update source but cannot run the shell scripts. No passing test-suite result is claimed.
- No Gradle task, APK build, CI workflow, Macrobenchmark, profile generation, LADB command, or physical-device run was started. A fresh authorized phone run is still required to determine whether AndroidX now discovers and flushes the target process. No startup-performance improvement is claimed.

References: [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) · [AndroidX ProfileInstallBroadcast source](https://android.googlesource.com/platform/frameworks/support/+/a349cb5de77484f27bec23731f681017514e2d64/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/ProfileInstallBroadcast.kt) · [AndroidX MacrobenchmarkScope source](https://android.googlesource.com/platform/frameworks/support/+/4af516a7e6a30e7e47560dbd4b388105446d26dc/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/MacrobenchmarkScope.kt) · [AndroidX dotted-process flush change and minimum ProfileInstaller version](https://github.com/androidx/androidx/commit/57db4296a492097b11d094da7c4f9167cd78ebfe)


## 22 November 2026 — deeper AndroidX flush-path trace

### What the exception actually proves

The exact `Process com.nuvio.media.benchmark never flushed profiles in any process` message is thrown by `CompilationMode.Partial.compileImpl` when its `hasFlushedArtProfiles` check remains false. In the AndroidX profile-flush implementation, that state is set only after `ProfileInstallBroadcast.saveProfilesForAllProcesses(packageName)` discovers at least one running target process and completes the save path. The process lookup calls `Shell.getRunningPidsAndProcessesForPackage`, which delegates to `Shell.pgrepLF` with escaped package dots. AndroidX runs `pgrep -l -f <pattern>` on API 23–35 and adds `-a` on API 36+ (`pgrep -l -f -a <pattern>`) because newer Toybox requires `-a` to print the full command line. It parses each result into PID and process name, accepting the exact package, `:<suffix>` child processes, and dotted-prefix process names only when no separately installed package owns that name. The dotted-prefix ownership check uses the instrumentation context's `PackageManager.getApplicationInfo`, so a shell-side `pm path` probe cannot reproduce its package-visibility context exactly. A zero-process result therefore leaves the flush flag false and produces this exact check failure.

This is materially different from a receiver failure. If a process is discovered but the `SAVE_PROFILE` broadcast is not received, AndroidX has a separate error path describing the missing/old ProfileInstaller receiver (or a rooted fallback). The observed `never flushed profiles` exception is not evidence that the receiver returned a bad result code. PR #15's standalone result-12 preflight could succeed while the later AndroidX process lookup still returns zero.

### App/dependency checks

- `androidApp/build.gradle.kts` includes `implementation(libs.androidx.profileinstaller)` and the version catalog pins ProfileInstaller `1.4.1`.
- The isolated benchmark application ID is `com.nuvio.media.benchmark` (the benchmark build types append `.benchmark` to `com.nuvio.media`). This matches the package in the exception.
- The main, Full and Play Store manifests have no `android:process` or `isolatedProcess` declarations, so the current benchmark target is expected to use only its main process. ProfileInstaller `1.4.1` is the stable release documented as fixing sequential multi-process Baseline Profile recording. However, the AndroidX dotted-subprocess fix used by Benchmark 1.6 source routes every non-main process through `BENCHMARK_OPERATION` / `SAVE_PROFILE` and declares `1.5.0-alpha01` as the minimum ProfileInstaller version. The repo still pins `1.4.1`; that is not a demonstrated issue for the current single-process manifests, but any future secondary-process declaration must trigger a dependency-compatibility review before profile capture is trusted.
- AndroidX Benchmark `1.6.0-alpha01` already includes the documented fix for Baseline Profile capture of process names without a colon pattern. `1.6.0-alpha02` release notes do not identify a further process-discovery fix. A dependency upgrade is not justified by the current evidence.

### Initial diagnostic gap — addressed in the API-aware follow-up

The first monitor sampled `ps -A` only and therefore did not reproduce AndroidX's actual `Shell.pgrepLF` lookup or parser. The follow-up now records the raw API-aware pgrep command/output/exit status beside each independent `ps -A` snapshot. Exact-package and colon-child classification mirrors AndroidX's string checks. Dotted-prefix classification is explicitly advisory: the shell runner uses `pm path`, while AndroidX uses the instrumentation context's `PackageManager.getApplicationInfo`; package visibility can differ. The regression fixtures now include successful discovery and a zero-match pgrep result. These source changes still require execution of the shell regression suite and a fresh device collection; they do not establish the device's actual flush-time result.

### What the supplied instrumentation log does and does not show

The recovered `instrumentation(5).log` contains 14 `startup` readiness records and seven `home_browse` readiness records, all with `iteration=0`. These are 21 successful readiness events, not 21 uniquely identified iterations. The old provenance file records APK byte hashes and a runner hash but no source commit, so the records cannot be attributed conclusively to the current branch's generator or AndroidX source. The iteration label should be treated as an instrumentation/provenance limitation until a fresh run from a known APK/source commit confirms it.

The `instrumentation(6).log` independently confirms the LADB foreground interruption: six startup readiness successes are followed by a 16.7-second `active_window_timeout` with `com.draco.ladb` as the active package; its Home Browse path then hits the old stale-node stack. That run must remain classified as an environment-interrupted run plus an old-APK stale-node failure, not as evidence against the current reacquisition implementation.

### Pull request status

PR #16 was closed without merging by the repository's automation. Its closing comment says the legacy app is being discontinued in favor of a Compose Multiplatform rewrite and that legacy-codebase pull requests are no longer being reviewed. There are no review submissions or inline review comments. The implementation remains on `fix/baseline-profile-flush-evidence-final-20261122`; it is not in `main-build-testing`.

### Next evidence required

Do not change production app code or weaken readiness. First capture the exact `pgrep -l -f` result that AndroidX relies on during the BaselineProfile flush, alongside `ps -A`, the active foreground package, and AndroidX/ProfileInstaller logcat. If `ps` shows the target but pgrep/parser output does not, investigate process-name formatting and AndroidX matching. If both show no target, investigate who/what terminated the app between the final readiness event and the five-second-later flush. If pgrep shows the target but AndroidX still reports zero, compare the exact command pattern, output parsing, and test APK version against the pinned AndroidX implementation. No root cause beyond the zero-process condition is claimed until that evidence is captured.

Additional references: [Android logcat output formats](https://developer.android.com/tools/logcat#outputFormat) · [AOSP logcat format implementation](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logcat/logcat.cpp) · [AOSP ART SignalCatcher SIGUSR1 profile-save implementation](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/signal_catcher.cc) · [AndroidX multi-process profile flush implementation](https://github.com/androidx/androidx/commit/de16b7c8489100703778a0028fd3ee89dc0efaef) · [AndroidX ProfileInstaller 1.4.1 release notes](https://developer.android.com/jetpack/androidx/releases/profileinstaller) · [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark) · [AndroidX Shell process discovery and package-prefix filtering source](https://android.googlesource.com/platform/frameworks/support/+/f921a7f3de987f2c92f1cf2a14b4e4dc2bc39f63/benchmark/benchmark-common/src/main/java/androidx/benchmark/Shell.kt) · [AndroidX dotted-subprocess flush fix](https://github.com/androidx/androidx/commit/57db4296a492097b11d094da7c4f9167cd78ebfe) · [Android package-visibility filtering](https://developer.android.com/training/package-visibility)


### 22 November 2026 — API-aware AndroidX process-discovery parity

The live, post-run and failure-snapshot logcat captures now use `-v threadtime` rather than `-v time`. Android's documented `threadtime` format includes both PID and TID; `time` includes only the process PID. This preserves the identifiers needed to correlate a `SAVE_PROFILE`/ART signal with the exact process discovered by AndroidX. A second filter gap was found: ART's `SignalCatcher::HandleSigUsr1` emits `SIGUSR1 forcing GC (no HPROF) and profile save` at INFO, under the `art` tag, after which it invokes `ProfileSaver::ForceProcessProfiles()`. The previous tag allowlist omitted `art`, so it could not retain this confirmation. All three captures now include `art:*` alongside Benchmark, ProfileInstaller and AndroidRuntime. The fake-device fixture emits separate PID/TID columns plus the ART signal-confirmation line, and asserts the live and post-run filter arguments.

A further source check found an API-level detail that the initial `ps -A` diagnostic did not model:

- AndroidX `Shell.pgrepLF` uses `pgrep -l -f <escaped-package-pattern>` on API 23–35.
- On API 36 and later it adds `-a`: `pgrep -l -f -a <escaped-package-pattern>`. AndroidX commit [`98d9090`](https://github.com/androidx/androidx/commit/98d9090a01e04dc730484f09cd073e4d82f73d35) records the Toybox change: `-a` is required to print the full command line.
- AndroidX parses each non-empty line into PID and process name. Its package filter accepts the exact package name and colon child processes. For dotted custom process names, it strips the colon suffix and checks every progressively longer dotted package prefix with PackageManager; a separately installed prefix causes rejection. The shell diagnostic checks the same progressively longer prefixes with `pm path`, rather than checking only the complete process name. This is a shell-side approximation, not an exact reproduction of AndroidX's installed-package decision: AndroidX calls `PackageManager.getApplicationInfo` through the instrumentation context, and Android 11+ package visibility filters results for apps targeting API 30+. The raw pgrep output is therefore stronger evidence than the shell's dotted-child classification; the actual AndroidX `Saving profiles for process ...` log remains the authoritative indication that a process reached the flush loop.

The phone runner's live process timeline now records, at the same approximately one-second samples:
1. The independent `ps -A` process-name snapshot.
   - The independent `ps -A` snapshot now includes dotted-prefix candidate names as well as exact and colon-child names. It records candidates only; a dotted name can belong to another installed package and is not proof of target ownership.
2. The exact API-aware AndroidX-equivalent `pgrep` command, raw output and exit status.
3. Parsed process-name classifications: exact/colon matches; dotted-child candidates marked accepted/rejected by the shell `pm path` approximation; or unrelated process names. Dotted-child labels are deliberately prefixed `SHELL_APPROX_` to avoid claiming exact AndroidX PackageManager parity.

The post-instrumentation failure snapshot also captures the AndroidX-equivalent lookup. This distinguishes a real zero-match result (pgrep exit status 1), a successful command with unexpectedly empty output, and a pgrep execution error (other non-zero status). Error text is retained as command-error evidence rather than misparsed as a PID/process row. It also allows direct comparison when `ps -A` lists the app but AndroidX's lookup does not.




Regression fixtures now cover API 33's command form, API 36+'s `-a` form, successful main/colon/dotted-child process parsing, dotted-child retention in the independent `ps -A` snapshot, rejection of unrelated pgrep candidates, shell-approximate rejection when an intermediate dotted package prefix is installed, a zero-match result while the independent `ps` fixture still lists the app, and a pgrep execution error whose stderr must not be parsed as a process row. The regression assertions now cover advisory dotted-child labels, unrelated pgrep candidates, dotted-child retention in the independent `ps -A` snapshot, and rejection of non-boundary package prefixes. The repository's full shell regression suite was not executed in this session: the GitHub source integration cannot run repository scripts, and no local checkout was available. Isolated POSIX-shell smoke checks passed for the same dotted-prefix classification logic and `ps -A` predicate, including main/colon/dotted/unrelated names and rejection of a non-boundary prefix. These focused snippets do not replace execution of the repository suite. An isolated fake-logcat smoke check also confirmed that the `threadtime` fixture retains separate PID/TID columns and that the live and post-run argument forms are recorded as expected; this did not execute the repository harness against the phone runner. No build, CI, instrumentation, profile collection, LADB command or physical-device run was started.

This is a diagnostic-parity improvement, not a confirmed production root-cause fix. The next authorized device run must establish the actual API-aware pgrep result at the collection window and correlate it with AndroidX `Saving profiles for process ...` log entries. If the process is discovered but no save log follows, investigate the broadcast/receiver path; if pgrep returns no process, investigate the process lifecycle between the final readiness event and the flush.
