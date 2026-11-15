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
