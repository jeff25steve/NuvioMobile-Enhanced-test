# Android cold-start critical-path review

Date: 12 November 2026
Branch: main-build-testing

## Findings
Grok's central diagnosis is correct: Android keeps the splash screen until StartupPreloader finishes, before MainActivity calls Compose setContent. The preloader waited for Library, watched, watch progress, Continue Watching preferences, Player, P2P, Server, Meta Screen, release notifications and Live TV as well as profile and Home snapshot work. MainAppContent repeated several feature ensureLoaded calls in its first composition, so changing only the preloader would leave a second bottleneck.

Home requires a narrower set:
- Profile cache first; HomeSnapshotStore.currentProfileKey depends on the active profile.
- Watched history, watch progress and Continue Watching preferences are consumed by Home.
- The profile-keyed Home snapshot must load after profile restoration.
- Downloader platform recovery remains parallel with Home-critical reads.

LibraryRepository.ensureLoaded prepares tracking providers and provider refresh work, but is not needed to draw Home's Continue Watching shelf. It moves after first draw. LibraryScreen retains its own ensure; the Home poster action overlay ensures Library before checking local/remote membership.

## Implementation
- Added a single-flight coordinator for critical and post-frame phases.
- Android Activity awaits only critical work before setContent.
- A Compose effect waits for a frame and starts post-frame warmup.
- Critical: profile cache, watched, watch progress, Continue Watching preferences and Home snapshot.
- Critical: lightweight Android downloader scheduler construction remains in parallel with Home-critical repository restoration; the transfer-record scan is deferred.
- Post-frame: Android transfer-record scan on IO, DownloadsRepository manifest restore, Live TV, Player, P2P, Server, Meta Screen and Library.
- Android root first-composition feature ensures are deferred; iOS eager behavior is preserved.
- Notification refresh waits for a frame on Android; iOS timing is preserved.
- LiveTvUiState records when playlist preferences are restored. Initial and native tab decisions wait for this signal, preventing a valid Live TV startup route from being redirected while the default state is visible.
- Home catalog refresh waits for the Server repository's first loaded revision.
- DownloadsUiState.isLoaded gates initial offline fallback until the active profile's saved manifest is restored.

## Regression tests added
- Coordinator: critical await does not run post-frame work; concurrent waiters share initialization; post-frame waits for critical completion and only starts once.
- Offline route: waits for manifest restore, Home readiness and known network state; does not repeat after handling.

## Research consulted
Context7: Compose Multiplatform frame/effect APIs and Kotlin Coroutines structured concurrency. Existing versions remain Compose Multiplatform 1.13.0-beta01, Kotlin 2.4.10 and kotlinx-coroutines 1.10.2; no dependency changes.

Exa / Android Developers:
- https://developer.android.com/topic/performance/appstartup/analysis-optimization
- https://developer.android.com/topic/performance/appstartup/best-practices
- https://developer.android.com/topic/performance/issues/launch-time
- https://developer.android.com/develop/ui/views/launch/splash-screen/migrate
- https://medium.com/androiddevelopers/dont-prewarm-app-features-118e0a2d4bb1

Android guidance warns background prewarming still contends for CPU, storage and network. No unrelated image/cache work is added.

## Follow-up optimization — 14 November 2026

Two further non-essential operations were removed from the Android pre-`setContent` path:

- Episode-release notification platform initialization now stores context only. It no longer performs the notification-manager channel check or first-install channel creation before Compose draws. The permission, scheduling, test-send, and due-delivery paths retain channel creation; due delivery performs the check on `Dispatchers.IO`.
- Add-on HTTP client construction is deferred until the first add-on request. Previously the provider built a default client and immediately replaced it with a second client configured with the 50 MiB cache. The provider now builds one configured client lazily. Both current request entry points already execute inside `Dispatchers.IO`.

The app pins OkHttp 4.12.0. Its tagged `Cache.kt` documents that journal loading is performed by explicit `Cache.initialize()`, while normal cache initialization is lazy on first use. Nuvio did not call `Cache.initialize()`; no startup disk-journal scan was claimed or optimized. The demonstrated saving is limited to removing the duplicate/eager client construction and moving its first creation off the UI startup path.

Regression tests cover deferred channel creation and lazy, single-instance client creation under concurrency. Context7 was attempted for the exact library documentation but its monthly quota was exhausted; Exa checked the tagged Square OkHttp source and official Android startup guidance.

No launch-time benchmark, Macrobenchmark, Gradle/Xcode test, build, CI run, or device verification was performed. The changes are expected to reduce pre-first-frame work, but the actual user-visible latency improvement is unmeasured.

## Validation
Focused regression tests are added but not run. No measured latency improvement is claimed; physical-device timing is required. No Gradle build, CI run or Macrobenchmark is claimed. No GitHub Actions workflow is dispatched.

## Follow-up optimization — defer Android transfer-record loading

The Android startup critical phase previously called `DownloadsPlatformDownloader.prepareForStartup()` on IO and awaited it before `setContent`. Although the work was off the main thread, constructing `AndroidDownloadScheduler` constructed `AndroidDownloadStore`, whose StateFlow initializer synchronously enumerated the transfer directory and decoded every persisted JSON record. The Activity still waited for this storage work to finish.

Changes:
- `AndroidDownloadStore` now initializes its transfer StateFlow lazily with Kotlin's synchronized lazy mode. Constructing the scheduler no longer scans or decodes transfer records.
- The critical phase still constructs the lightweight Android scheduler in parallel with Home-critical work, but lazy store initialization means it no longer scans transfer files.
- The post-frame phase explicitly touches the transfer store on `Dispatchers.IO` before restoring the common DownloadsRepository manifest on its existing Main-thread path. `DownloadsScreen` also awaits this preload on IO if opened before post-frame warm-up completes.
- The iOS implementation of the new preload hook is a no-op. iOS background URLSession behavior and the existing profile-manifest restore ordering are unchanged.
- First access remains single-initialization and thread-safe; subsequent reads use the same StateFlow. Download record format, IDs, generations, scheduling, resume and pause behavior are unchanged.

Regression coverage added (not executed):
- Store construction leaves the transfer store uninitialized.
- First access initializes the store.
- A persisted transfer is restored from disk on first access.

Kotlin's official standard-library documentation states that `LazyThreadSafetyMode.SYNCHRONIZED` uses a lock to ensure one initializer and safe publication to all threads: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-lazy-thread-safety-mode/-s-y-n-c-h-r-o-n-i-z-e-d/. Context7 was attempted but its monthly quota was exhausted, so the exact-library Context7 requirement could not be satisfied for this cycle. No dependency was changed.

Expected benefit: transfer-directory enumeration and JSON decoding no longer delay the initial Android screen. The work is intentionally retained after first frame and remains on IO; no reduction in total work or measured launch-time improvement is claimed.

## First-frame safety follow-up

A final call-site audit found that users can open Downloads immediately after the first frame, before post-frame warm-up completes. To avoid a race in which the lazy store would otherwise be loaded synchronously from the screen's composition path, `DownloadsScreen` now awaits the transfer-store preload on IO before restoring the common manifest. The screen retains its normal list/empty state after restoration and shows a loading indicator while restoration is pending. Lightweight scheduler construction remains critical, preserving immediate availability of the platform scheduler; only the file scan and JSON decoding move out of the first-frame wait.


## Sentry Android startup optimization — 14 November 2026

The startup path still called `SentryAndroid.init(application)` synchronously from `MainActivity.onCreate`, before `super.onCreate`. The app was pinned to Sentry Android SDK 8.47.0 and Sentry Android Gradle Plugin 6.14.0.

Integrated:
- Upgraded the existing `sentry-android` dependency from 8.47.0 to 8.64.0.
- Upgraded the existing Sentry Android Gradle Plugin from 6.14.0 to 6.27.0.
- Explicitly enabled `runtimeOptimizations` in the existing Sentry plugin block. The plugin can resolve manifest metadata and optional SDK class availability at build time, avoiding corresponding runtime manifest/reflection work where supported.
- Preserved early Sentry initialization in Activity startup, so startup crashes remain eligible for reporting. No background-thread or post-first-frame deferral was introduced.
- Preserved `autoInstallation=false`, `tracingInstrumentation=false`, DSN handling, ProGuard mapping configuration, telemetry opt-out, PII/screenshot/view-hierarchy settings, event filtering, and breadcrumb/network settings.

Evidence:
- Sentry's 20 October 2026 engineering report measured 20–30% lower Sentry SDK initialization duration comparing SDK 8.43.0 with 8.54.0, using the Sentry team's Macrobenchmark on a Pixel 3. This is a result for Sentry initialization, not a promised 20–30% improvement in Nuvio's total launch time: https://blog.sentry.io/android-sdk-faster-startup/.
- Sentry SDK 8.64.0 release notes (11 November 2026) include fixes to prevent main-thread stalls during app startup/foreground transitions while registering network/telephony listeners and to avoid blocking Activity `onCreate` while the frame-metrics thread starts: https://github.com/getsentry/sentry-java/releases/tag/8.64.0.
- Sentry Android Gradle Plugin 6.27.0 is the latest release observed (13 November 2026): https://github.com/getsentry/sentry-android-gradle-plugin/releases/tag/6.27.0. The plugin's documented runtime optimizations resolve manifest metadata and optional class availability at build time; default runtime optimization configuration is enabled, and Nuvio now states this explicitly.
- Maven Central publishes `io.sentry:sentry-android:8.64.0`: https://central.sonatype.com/artifact/io.sentry/sentry-android/8.64.0.
- Context7 resolution was attempted for the exact Sentry SDK/plugin documentation but the monthly quota was exhausted. Official Sentry release notes, source, plugin documentation and Maven Central were used instead.

Validation boundary:
- Static GitHub review only. No Gradle build, compile, tests, Macrobenchmark, device run or CI was performed, as requested.
- The SDK and plugin release tags are verified, but Nuvio's binary compatibility and generated manifest/bytecode optimizations remain unverified until an authorized build.
- No Nuvio launch-time improvement is measured or claimed. The expected benefit is lower Sentry initialization cost and fewer startup main-thread stalls; actual TTID/TTFD impact needs an authorized device benchmark.


## Android Baseline Profile preparation — 14 November 2026

The Android app included AndroidX ProfileInstaller 1.4.1 and a legacy app-owned `androidApp/src/main/baseline-prof.txt` containing approximately 1.4 MB of source rules (present since September 2026). What was missing was a Baseline Profile producer module, per-flavor `fullRelease` / `playstoreRelease` profile generation, and a Macrobenchmark collection journey. ProfileInstaller installs profile data packaged with an app; it does not generate Nuvio-specific profile rules.

Integrated:
- Added the stable AndroidX Baseline Profile Gradle plugin and Macrobenchmark 1.5.0 to the version catalog, together with the Android test plugin and its test dependencies.
- Added the isolated `:baselineProfile` producer module. It targets `:androidApp` and mirrors the app's `distribution` dimension with `full` and `playstore` flavors.
- Applied the consumer plugin to `:androidApp`, connected the producer through the `baselineProfile` configuration, and enabled source-controlled variant-specific profile output.
- Explicitly disabled automatic generation during release assembly. Normal release builds do not launch an emulator or run profile instrumentation tests.
- Added a single startup-only `BaselineProfileRule` collection test for package `com.nuvio.media`, using the actual launcher start and marking that journey for the Startup Profile. The app's debug application-ID override remains untouched; generation targets the non-minified release profile variant.

Why this matters:
- ART can AOT-compile code paths in a shipped Baseline Profile for users' first launch after install/update. Android's overview describes roughly 30% faster code execution from the first launch as a general platform result, not an Nuvio-specific guarantee: https://developer.android.com/topic/performance/baselineprofiles/overview.
- Startup Profiles additionally inform DEX layout for the app's startup path. Android recommends combining Baseline Profiles and Startup Profiles for startup optimization: https://developer.android.com/topic/performance/baselineprofiles/overview.
- Macrobenchmark 1.5.0 is the current stable release, and its 1.5.0 release line includes the AGP 9 new-DSL compatibility fix: https://developer.android.com/jetpack/androidx/releases/benchmark.
- AGP 9.4.0 requires Gradle 9.6.0 or later; this repository's wrapper is Gradle 9.8.2: https://developer.android.com/build/releases/agp-9-4-0-release-notes.

Validation boundary:
- Static repository review only. No Gradle configuration, build, compile, test, Macrobenchmark, emulator/device operation, or CI task was run.
- The source integration creates the mechanism to produce profiles; it does not yet ship a usable Nuvio profile. The existing empty legacy `src/main/baseline-prof.txt` remains unchanged until real generated rules are captured and reviewed.
- Generated `baseline-prof.txt` / `startup-prof.txt` files for both flavors, binary packaging, and build/plugin compatibility still need authorized validation.
- No Nuvio startup speedup is measured or claimed. The Android 30% figure is general guidance only. A repeated cold-start TTID/TTFD comparison on the same physical device is required before claiming benefit.
- Context7 was attempted for the exact AndroidX Baseline Profile plugin documentation but returned a monthly quota limit. Android Developers' official docs, AndroidX release notes and current AndroidX source were used instead.

## Baseline Profile quality hardening — 14 November 2026

Latest API/source review confirmed the stable Macrobenchmark 1.5.0 collection API and exposed two quality issues in the initial producer setup.

### Improvement cycle 40 — Make collection deterministic and reject unstable rules

Integrated:
- BaselineProfileGenerator.startup() now calls pressHome() before startActivityAndWait() on every collection iteration. The profile collection starts from the device launcher instead of depending on whichever app screen happened to be foregrounded.
- Set strictStability = true while retaining 15 maximum iterations and 3 stable iterations. If the profile does not stabilize within the configured limit, collection fails rather than silently accepting an unstable rule set.
- Raised only the :baselineProfile instrumentation module's minSdk to maxOf(28, app minSdk). AndroidX documents collection on API 33+ without root, or API 28+ on rooted devices. The actual Nuvio app minSdk remains 24; this change affects only the generator/test APK.

API evidence:
- The stable BaselineProfileRule API includes the Java-callable overload with package name, iteration limits, output prefix, Startup Profile flag, strict stability flag, filter predicate and profile block. The existing Java call matches that overload.
- AndroidX source documents pressHome() as the launcher reset for a startup journey and strictStability as the option to enforce stable output.
- API reference: https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule
- AndroidX API/source and release notes: https://developer.android.com/jetpack/androidx/releases/benchmark

### Improvement cycle 41 — Add a cold-start A/B benchmark that requires the profile

Integrated:
- Added StartupBenchmark with two explicit scenarios:
  - CompilationMode.None() — cold startup with no AOT compilation.
  - CompilationMode.Partial(BaselineProfileMode.Require) — cold startup that must install and use the packaged Baseline Profile.
- Both scenarios use StartupMode.COLD, reset to Home before launch, collect StartupTimingMetric, and run 10 iterations.
- The Require mode fails when the profile or ProfileInstaller is missing instead of silently falling back to an unprofiled run.
- The benchmark is an explicit instrumentation test. It does not run as part of release assembly or CI. Run with the Macrobenchmark instrumentation rule enabled (androidx.benchmark.enabledRules=macrobenchmark) on a physical device after a generated profile has been built into the target variant.

Measurement caveat:
- Android's current startup guidance recommends comparing CompilationMode.None() with CompilationMode.Partial(BaselineProfileMode.Require) and using median cold-start results on a physical device: https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile.
- StartupTimingMetric can report TTID and TTFD, but TTFD requires the app to explicitly signal fully drawn. Current Android MainActivity/Compose app wiring does not pass an app-ready callback to Android's Compose ReportDrawnWhen API. The current benchmark therefore guarantees TTID; do not treat TTFD as validated until a separate, correctly scoped readiness signal is added and the benchmark waits for that state.
- Android's Compose-specific guidance recommends ReportDrawn, ReportDrawnWhen or ReportDrawnAfter rather than manually calling Activity.reportFullyDrawn(): https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics.
- The initial profile journey still represents the currently installed app's launcher state. It does not fabricate an authenticated account, onboarding completion, catalog data, add-on setup or network response. A repeatable, representative signed-in Home journey needs a safe test fixture before it can be included.

Validation boundary:
- Static API/source and remote repository review only. No Gradle configuration, compilation, test, Baseline Profile generation, Macrobenchmark, emulator/device operation, or CI was run.
- No profile file has been generated or packaged yet. No Nuvio performance result or speedup is claimed.
- The benchmark harness is added but not executed; its build compatibility and task wiring must be validated in an authorized Gradle/device session.
- Context7 resolution for the exact AndroidX Macrobenchmark 1.5.0 documentation again returned the service monthly-quota error. The official Android Developers API/reference pages, AndroidX source/API signatures, AndroidX release notes and the pinned repository versions were consulted instead.


### Improvement cycle 42 — Connect real Compose readiness to Android TTFD

Integrated:
- MainActivity now passes App's existing onAppReady callback into remembered Compose state and uses ReportDrawnWhen. The Activity reports fully drawn only after AppGate's existing main-content-ready condition becomes true; no timer, fake delay, network shortcut or changed startup order was added.
- A Compose semantics test tag, nuvio_startup_ready, is exposed as a resource ID only when that same state is true. It is test infrastructure, not visible UI or an accessibility announcement.
- The marker is attached to the existing full-size root wrapper rather than adding an extra nested layout wrapper.
- StartupBenchmark waits for the marker after startActivityAndWait(), allowing StartupTimingMetric to capture TTFD as well as TTID. A 30-second condition-based timeout fails clearly when the signed-in Home journey is not ready.
- This benchmark must use a preconfigured signed-in test install. Logged-out/onboarding states do not satisfy the Home readiness marker and are deliberately not counted as successful Home startup.

Evidence:
- Android StartupTimingMetric defines TTFD as the interval until the app reports fully drawn: https://developer.android.com/topic/performance/benchmarking-macrobenchmark-metrics.
- Android's Macrobenchmark codelab explicitly requires the benchmark measure block to wait for the content-ready UI element after startActivityAndWait(), otherwise it may finish before TTFD is recorded: https://developer.android.com/codelabs/android-macrobenchmark-inspect.
- Compose ReportDrawnWhen is the recommended state-driven API: https://developer.android.com/reference/kotlin/androidx/activity/compose/ReportDrawnWhen.composable.
- Compose-to-UIAutomator testTag resource-ID mapping is documented here: https://developer.android.com/develop/ui/compose/testing/interoperability.
- UI Automator stable release 2.4.1 (4 November 2026) is pinned for the marker wait: https://developer.android.com/jetpack/androidx/releases/test-uiautomator.

Validation boundary:
- Static remote source and API review only. No build, Gradle configuration, compile, test, profile generation, Macrobenchmark, device operation or CI was run.
- Context7 was attempted for AndroidX Activity Compose and UI Automator but its monthly quota was exhausted. Exa research of Android Developers API/codelab docs and AndroidX official release references was used instead.
- The TTFD path is wired in source but remains unverified at runtime until an authorized physical-device benchmark. No speedup is claimed.


### Improvement cycle 43 — Do not mark Home fully drawn underneath an exiting launch overlay

Root-cause review found that MainAppContent's prior onRootContentReady callback forwarded initialHomeReady immediately. The launch overlay is driven by a separate MutableTransitionState and can still be in its exit animation after the underlying Home data is ready. That made the newly added TTFD signal potentially early by the remaining overlay animation duration.

Integrated:
- The readiness effect now observes launchOverlayState.currentState and launchOverlayState.isIdle in addition to rootContentReady.
- It reports true only when the Home root is ready AND the launch overlay is fully idle and no longer visible. It reports false for loading and while the exit transition is running.
- This changes only the readiness callback's meaning to match the visible, interactive app; it does not delay rendering, alter the overlay animation, or change navigation.
- MainActivity's ReportDrawnWhen and UI Automator marker now inherit this stricter visible-ready condition without another timer or sleep.

Validation boundary:
- Static source inspection only; no Compose test, build, Gradle configuration, Macrobenchmark, device operation or CI was run.
- This corrects a measurement semantic gap. It is not a claim that app startup itself is faster.


## Authorized R8 startup-profile build follow-up — 14 November 2026

The user authorized an optimized Android build for this validation. The measurement path now distinguishes production signing from the build type required for Macrobenchmark:

- AndroidX Baseline Profile 1.5.0 creates `nonMinifiedRelease` for profile collection and `benchmarkRelease` for measurement. The latter is non-debuggable, profileable, R8/minified and resource-shrunk. Nuvio explicitly assigns the Android debug signing config to both profiling-only build types; production release signing remains unchanged. This is the supported release-mirrored benchmark build, not a debug build and not a production-signed release APK.
- The application Gradle split predicate now excludes `BenchmarkRelease` and `NonMinifiedRelease` task variants, keeping benchmark APKs universal while preserving ABI splits for actual `assemble*Release` production outputs.
- The Baseline Profile generator waits for the existing `nuvio_startup_ready` UI Automator resource marker. It fails if the signed-in Home state is not reached, rather than accepting onboarding, login or the first frame as a completed profile journey.
- The physical-device runner generates per-flavor profiles with `generateFullReleaseBaselineProfile` and `generatePlaystoreReleaseBaselineProfile`, then runs 10 cold starts with `CompilationMode.None()` and 10 with `CompilationMode.Partial(BaselineProfileMode.Require)` on each flavor. It uses the same package, device and signed-in install across both arms, checks the compiled profile against Android's 1.5 MB binary limit, and retains raw Gradle benchmark logs.
- Generated profile output is variant-specific: `androidApp/src/fullRelease/generated/baselineProfiles` and `androidApp/src/playstoreRelease/generated/baselineProfiles`. The existing shared `src/main/baseline-prof.txt` is retained; it is not overwritten or removed by this measurement preparation.

Build status at note update: the GitHub Actions job for R8 benchmark APK packaging is compiling the `full` benchmark variant. Earlier attempts to build production-signed APKs stopped before compilation because this fork has no production keystore/local-properties secrets. The workflow was therefore changed to package the R8 `benchmarkRelease` variants using that explicit debug signing config; production release signing remains unchanged. A benchmark-key APK cannot update a production-key-installed copy; a one-time uninstall and sign-in to the benchmark build is needed before physical testing. The `full` and `playstore` benchmark variants share the benchmark signing key and package ID, so the signed-in benchmark test state should then be retained between them.

No physical-device Macrobenchmark has run in this environment. No TTID/TTFD medians or startup improvement are claimed, and no newly generated profile is accepted or committed until the same-device signed-in comparison shows repeatable improvement in both metrics without regressions.

References:
- AndroidX build variants and per-variant profile output: https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- Release-like, minified and profileable Macrobenchmark targets: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- TTID and TTFD comparison guidance: https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile


## R8 benchmark APK build verified — 14 November 2026

GitHub Actions run 43645267759 completed successfully from commit 88bdfa82d650ede1fde0ee779370f589ee558517.

Verified completed steps:
- assembleFullBenchmarkRelease and matching Macrobenchmark instrumentation APK.
- assemblePlaystoreBenchmarkRelease and matching Macrobenchmark instrumentation APK.
- Phone bundle assembly, SHA-256 manifest generation and artifact upload.

Artifact:
- ID: 14134457830
- Name: nuvio-android-startup-profile-88bdfa82d650ede1fde0ee779370f589ee558517
- Size: 344,398,688 bytes
- Expiry: 28 November 2026
- SHA-256: 489a7409b7608b96d7e08064b99c54f207013405e190e295811b6e4e69dcc702

The downloaded archive was checked locally: all four APK entries, the start-here instructions, checksum manifest and runner script passed ZIP integrity testing. It contains both R8 benchmarkRelease app/test pairs. This verifies APK production, not a physical-device performance result.

Still open:
- Run the updated branch's scripts/run-android-startup-profile-benchmark.sh with exactly one supported physical Android device connected over ADB.
- Sign in to the benchmark installation and allow the generator and Macrobenchmark to run both flavors.
- Review TTID/TTFD medians and binary profile sizes; do not commit or retain generated profiles unless both metrics improve repeatably without regressions.
- No physical-device run has yet been performed; no Nuvio startup speedup is claimed.


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


## Benchmark runner follow-up review — 14 November 2026

The first two phone-bundle workflow dispatches failed before Gradle compilation:

- Run [43651971737](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43651971737) stopped at `bash -n` because the desktop runner's `find` expression had doubled escapes around its parentheses and semicolon. This was repaired on `main-build-testing` in `ef269d2e31c8b4f32bf391aa6c1bb8cbbf45a831`.
- Run [43652152841](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43652152841) passed the desktop syntax check but the mocked phone-runner test resolved its script from the wrong directory. This was repaired on `main-build-testing` in `d0d6017cae697ae4c87940000d631e9be7db123f`.
- Run [43652201558](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43652201558) passed the pre-build validation and Rust target setup, then entered the full non-minified profile-capture APK task. Its last observed status was `in_progress` at 23:14:13 UTC. It was already active when this review began; this review did not dispatch or rerun it.

A further static audit found two desktop-runner issues and fixed them on the isolated review branch:

1. All six ADB output cleanup commands now use `tr -d '\\r'` rather than `tr -d '\\\\r'`. GNU `tr` previously received a literal backslash plus the letter `r`, leaving carriage-return bytes in API level, model, hardware, emulator and root-check strings.
2. The desktop runner now collects profiles through `generateFullNonMinifiedReleaseBaselineProfile` and `generatePlaystoreNonMinifiedReleaseBaselineProfile`, and inspects `src/<flavor>NonMinifiedRelease/generated/baselineProfiles`. Its prior `generate*ReleaseBaselineProfile` tasks and `src/<flavor>Release` path selected the production release build type instead of the isolated `.benchmark` profile-capture build type. This aligns the desktop runner with the APK pairs used by the phone-only workflow and leaves production `com.nuvio.media` isolated.

The mocked runner test now asserts the CR escape, both non-minified task names and the correct generated source-set path. The new checks were exercised with a focused local fixture: they rejected the old task/path and accepted the corrected variant. The CR-removal behavior was confirmed with a literal CR byte; representative updated shell fragments passed `bash -n`. The complete repository mock suite has not been rerun locally after these new assertions were added.

The corrections are committed on `fix/android-startup-profile-shell-validation` pending final review and fast-forward integration. No build or CI was started by this follow-up. Context7 lookup for AndroidX Baseline Profile 1.5.0 hit the monthly quota; Android Developers' official Baseline Profile variant documentation and AndroidX plugin source were used through Exa instead. No device performance result or startup speedup is claimed.

References:
- https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- https://www.gnu.org/software/bash/manual/bash.html#Quoting


### Integration record — 14 November 2026

- Final review compared `fix/android-startup-profile-shell-validation` against the then-current `main-build-testing` head `8dca68e7b1c92997af5d9eb6e4b2187872d19c4e`: 8 commits ahead, 0 behind, with changes limited to the desktop benchmark runner, its mock-test script and four engineering/design notes.
- Fast-forward integrated the reviewed branch into `main-build-testing` at `ef23a761d5a27b40866ed0bace6cb5cdcb242797`. No force update or merge conflict was used.
- The integrated diff does not modify `androidApp/build.gradle.kts`, application IDs, manifests, Kotlin/Compose app source, the production release signing configuration or the phone-only runner.
- No new Actions run was triggered by this integration. The only observed active benchmark run remains [43652201558](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43652201558), which was already in flight from the separate CI-dispatch branch. At the latest check, both `nonMinifiedRelease` profile-capture build steps had succeeded and `Build full R8 benchmark APK pair` was in progress. Do not treat this as a completed artifact until the workflow reports success and the artifact is verified.
- The full updated mock suite has not been rerun locally after adding the new assertions. The focused local RED/GREEN regression fixture and shell syntax fixture passed; no Gradle build, CI run, Macrobenchmark or device test was started by this integration.
