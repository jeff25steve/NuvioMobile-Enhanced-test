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

1. All six ADB output cleanup commands now use `tr -d '\r'` rather than `tr -d '\\r'`. GNU `tr` previously received a literal backslash plus the letter `r`, leaving carriage-return bytes in API level, model, hardware, emulator and root-check strings.
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


## Evidence-based startup follow-up — 15 November 2026

A source-only cross-check against Android's current startup guidance and the Gojek/Reddit/Sentry practitioner reports identified one useful next audit surface, but did not establish a safe code change.

### MainActivity initializer inventory

At branch HEAD `8eae9b6290df3041cf998a59f21ff5ea6311d835`, `MainActivity.onCreate` calls 78 explicit `.initialize(...)` methods before `showContent()` and the first Compose composition. This is worth inspecting in the next Startup Insights/Perfetto capture, but it must not be treated as 78 expensive initializations. Representative implementations inspected in this pass are lightweight context/reference setup:

- `P2pStreamingEngine.initialize` stores the application context; engine creation is deferred until a stream/cache operation.
- `AppFileCache.initialize` stores a cache-directory reference.
- `EpisodeReleaseNotificationPlatform.initialize` and `AndroidAppUpdaterPlatform.initialize` store application context; the notification HTTP client is lazy.
- `ProfileBiometricAuth.initialize` stores a weak activity reference and launches legacy Keystore cleanup on an IO coroutine rather than blocking the main thread.
- `DownloadsPlatformDownloader.initialize` stores context and initializes export support; scheduler/store preparation is separately awaited by the startup preloader.

The list therefore needs per-call trace evidence before any initializer is moved. The call count alone does not demonstrate a launch regression.

### Existing startup split is already aligned with guidance

The current `StartupPreloader` awaits profile cache, watched data, watch progress, continue-watching preferences and the Home snapshot in its critical phase, while starting download-platform preparation concurrently. Its post-frame phase warms Live TV, player/P2P settings, servers, metadata settings, library and download transfer state. Android's current guidance supports deferring nonessential work after the first frame, but Nuvio already has this split; moving additional work requires proving that it is not needed for first Home readiness, offline routing, or notification/download correctness.

The application-owned manifests inspected here declare a FileProvider, while dependency-merged manifest providers cannot be confirmed without a merged-manifest artifact. Do not add AndroidX App Startup solely to follow generic advice: Gojek's 2026 report says its own adoption saved only a few milliseconds and was dropped. First establish whether Nuvio has multiple relevant eager providers and whether they are on the measured path.

### Evidence-led priority

1. R8 Configuration Analyzer report for the broad package keep rules (already identified in the benchmark note).
2. Primary DEX Startup Profile verification in the next authorized artifact.
3. Perfetto inspection of the 78 pre-composition initializer calls and the critical preloader steps; optimize only a measured expensive operation.

No runtime source or configuration change was made in this pass. No build, Gradle task, benchmark, device session or CI was run.

References: [Android startup best practices](https://developer.android.com/topic/performance/appstartup/best-practices), [Gojek Project Butter](https://www.gojek.io/blog/project-butter-a-journey-for-app-performance-improvement), [Reddit R8 case study](https://developer.android.com/blog/posts/how-reddit-used-the-r8-optimizer-for-high-impact-performance-improvements), [Sentry Android startup report](https://blog.sentry.io/android-sdk-faster-startup/).


## Benchmark fidelity audit — 15 November 2026, 22:36 UTC

A fresh inspection of the exact benchmark variant configuration found a higher-priority issue than changing runtime startup code:

1. `androidApp/build.gradle.kts` configures production `release` with R8 minification/resource shrinking (both default true) and `composeApp/proguard-rules.pro`. The custom `benchmarkRelease` build type only sets `.benchmark` application ID suffix and debug signing; it does not use `initWith(release)`. Android's AGP guidance says `initWith` is the mechanism for copying a build type's configuration. The phone runner uses `BenchmarkRelease` for Macrobenchmark, so the measured artifact does not inherit the explicitly configured production optimizer settings. Recommended correction: inherit release configuration, then override only suffix/signing; keep `nonMinifiedRelease` for profile collection. Regression-check both flavors, R8/resource shrink flags, profile packaging, non-debuggable/profileable state and install identity before any benchmark.
2. API 33 unrooted Macrobenchmark reset uninstalls and reinstalls the target APK, which clears app data. Nuvio's current startup benchmark requires an authenticated Home route. The runner currently accepts API 33 and reports only an ART comparability caveat after the run. It should preflight root capability and reject API 33 unrooted sessions for this authenticated Home benchmark; API 34+ is the preferred non-rooted path. AndroidX source documents the root vs reinstall distinction, and Android's current Macrobenchmark guide recommends API 34+ to persist state.
3. AndroidX Benchmark 1.5.0 exposes `ArtMetric` for JIT compilation, runtime class verification and supported-device class loading. Nuvio currently records only StartupTimingMetric/TTID/TTFD plus Startup Insights. Adding ArtMetric to both arms is a useful benchmark-only next step to check whether the packaged profile reduces runtime JIT/class-load work. Class-load tracepoints require API 35+ or API 31+ with ART Mainline >= `341511000`; optional metrics must remain optional. The 1.5.0 release includes an R8/AGP 9.1+ ArtMetric class-load fix, relevant to Nuvio's AGP 9.4.0.
4. The two JUnit arms each run ten iterations in separate batches. Sentry's October 2026 report used four interleaved rounds of ten runs per version with locked clocks. For Nuvio, repeat paired sessions with reversed/balanced arm order and retain per-arm environment snapshots/traces before claiming small gains.

No source/build configuration was changed because no local checkout was available to run the required test-first regression checks. These findings are documented in `android-startup-benchmark-phone-fix-2026-11-15.md`. No Gradle task, build, device benchmark or CI was run.

References: [AGP build variants](https://developer.android.com/build/build-variants), [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [AndroidX CompilationMode source](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt), [ArtMetric API](https://developer.android.com/reference/androidx/benchmark/macro/ArtMetric), [Benchmark 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/benchmark), [Sentry methodology](https://blog.sentry.io/android-sdk-faster-startup/).


## Correction — generated benchmarkRelease is plugin-configured — 15 November 2026, 23:10 UTC

Withdraw the previous statement that Nuvio's custom `benchmarkRelease` block omits R8 because it lacks `initWith(release)`. The pinned AndroidX Baseline Profile Gradle plugin 1.5.0 creates and configures this build type. Android's official plugin guide explicitly says these generated variants are configured for their use case and shows a custom `create("benchmarkRelease")` block changing only signingConfig. Nuvio's signing and applicationIdSuffix overrides follow this customization model. Do not add `initWith` based on the earlier audit.

The concrete outstanding checks are artifact-level: confirm the resolved benchmark APK is non-debuggable/profileable and minified with the intended release ProGuard rules, and confirm it uses the isolated benchmark package/signing identity. These require an authorized diagnostic build; no such build was run.

API 33: the app-data-loss path applies only when AndroidX Macrobenchmark's instrumentation shell is neither root nor able to run `su root id`. Rooted API 33 uses `cmd package compile --reset` and preserves app data. The outer LADB shell's UID alone does not establish the instrumentation shell's root capability. API 34+ remains the simplest non-rooted reproducible benchmark platform.

References: [Baseline Profile plugin configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles), [Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [AndroidX CompilationMode source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt).

## 15 November 2026 — deeper benchmark mechanics and launch-path cross-check

### Confirmed benchmark strengths

- The target source manifest explicitly declares `<profileable android:shell="true" />`. This enables shell profiling at source level; the final merged APK manifest still needs artifact-level verification.
- Nuvio's Compose TTFD contract is correctly connected: `MainActivity` uses `ReportDrawnWhen` tied to the `appReady` value received from AppGate. The UI Automator marker `nuvio_startup_ready` is emitted from the same state, and the benchmark also waits for `nuvio_benchmark_home`. TTID therefore remains first frame while TTFD waits for the usable Home state.
- AndroidX `StartupMode.COLD` clears the target shader cache, kills the target process, drops the kernel page cache, and verifies the process is stopped before each measured launch. Nuvio's ProfileInstaller 1.4.1 exceeds the ProfileInstaller minimum for the shader-cache broadcast. API 31+ supports the kernel page-cache operation without root, so the runner's API 33+ device floor is covered. Do not add duplicate cache clearing to the shell runner.
- These controls are separate from compilation-state preparation. API 33's unrooted pre-34 reinstall fallback can still erase the authenticated target app's data before measurement; a rooted instrumentation shell instead uses `cmd package compile --reset` and preserves it. An outer LADB shell UID is not proof of the instrumentation shell's root or `su` capability.

### ART diagnostics and reporting contract

AndroidX Benchmark 1.5.0 `ArtMetric` is a public API. Its exact result names are `artJitCount`, `artJitSumMs`, `artVerifyClassCount`, `artVerifyClassSumMs`, and conditionally `artClassLoadCount` / `artClassLoadSumMs`. Class-load data requires API 35+, or API 31+ with ART Mainline >= `341511000`. Release classes are normally verified at install time, so runtime class-verification metrics may legitimately be zero. Nuvio currently captures only `StartupTimingMetric` and its phone report only parses TTID/TTFD; optional ART output would need parser and regression-fixture support before it is useful. This is a benchmark instrumentation improvement, not an app runtime change.

AndroidX 1.5.0 also supports `androidx.benchmark.requireLockedClocks` for rooted physical devices. The current runner records the reported `cpuLocked` field but does not attempt to lock a non-rooted handset. Macrobenchmark is an end-to-end app benchmark, and Android guidance reserves forced clock locking primarily for Microbenchmark. Keep clock state as context rather than adding a root-only requirement to normal phone runs.

### New practice evidence

Genvi's Oct/Nov 2026 report measured 7.3 s to 3.2 s on its release app after deferring large tool modules, tab setup and the second blank-to-populated Home draw; its sample was five launches and should be treated directionally. TrulyMadly's Nov 7 practitioner post describes skipping unnecessary launch network calls when cache is fresh, removing obsolete screen configuration and moving hidden screens/noncritical work off the first-frame path, but provides no comparable timing series. RKMovies' Nov 13 post reports 250–350 ms TTID on one low-end phone after minimizing launch providers and delaying video surface creation; it is self-published and not a comparable Nuvio baseline. These are hypotheses to test against Nuvio traces, not a prescription to disable providers or defer required Home/offline state.

### Next evidence gates

1. R8 Configuration Analyzer for the minified production variants; AGP 9.4 meets the minimum, but no Gradle task was run.
2. Confirm Startup Profile primary-DEX placement in the built artifact.
3. Extend ArtMetric only with optional-field parser coverage and per-arm reporting.
4. Inspect actual Perfetto/Startup Insights before touching startup initialization. Keep paired sessions and raw traces for borderline gains.

References: [Macrobenchmark cold-start source](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt), [MacrobenchmarkScope cache APIs](https://developer.android.com/reference/androidx/benchmark/macro/MacrobenchmarkScope), [ArtMetric implementation](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Metric.kt), [R8 Configuration Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer), [Genvi case study](https://genvi.co/blog/cutting-phone-cold-start-from-7-to-3-seconds), [TrulyMadly practitioner report](https://www.linkedin.com/posts/pulkitloya_android-kotlin-androiddevelopment-activity-7524833478069399552-UzGB), [RKMovies practitioner post](https://dev.to/hammiwor/why-my-1.17-mb-player-opens-in-under-400-ms-cold-zero-content-providers-lazy-init-and-a-startup-1i1h).

## 15 November 2026, 23:38 UTC — field variance and ART reset cross-check

- The phone runner already captures uptime (`/proc/uptime`), `sys.boot_completed`, battery and thermal-service data before and after the whole Macrobenchmark invocation. Do not add duplicate collection. The current snapshots do not provide per-arm timestamps or stratified summaries, so interpret the raw snapshots and, if needed, repeat complete paired runs both shortly after reboot and after several hours of uptime.
- Shubham Singh Bisht's October 2026 field report (one week of telemetry from thousands of classroom panels) found more than half of cold launches within two minutes of boot; the median was about 4.5 s overall and about 2.5 s after four hours uptime. It also showed how API-level/device cohort mismatch can make Play Console and local lab results disagree. The app-specific changes (WebView inflation, hidden ComposeView composition, 4K View hierarchy, bitmap decoding) are not directly transferable to Nuvio's Compose Multiplatform startup.
- The same report shows why TTID alone is insufficient: background initialization can compete with the UI thread on low-core devices and worsen the first usable/full-display result. Keep TTID and Nuvio's Home-ready TTFD together as acceptance criteria.
- Google/Grab's 19 Oct 2026 case study reports ~35% cumulative cold-start improvement from R8, Baseline/Startup Profiles, Full Mode and keep-rule refinement. Its key additional lesson is that Cloud Profiles can miss app-specific startup journeys; explicit Baseline Profile coverage of those journeys complements them. Nuvio already has the profile infrastructure, so Analyzer output and compiled artifact inspection are the evidence gates.
- Pinned AndroidX Benchmark 1.5.0 already includes ART runtime-image controls for API 34+ so the no-compilation arm does not become artificially faster after the first launch. It clears runtime images where supported and uses a poisoning workaround where necessary. Do not add custom ART image clearing.
- AndroidX Benchmark 1.6.0-alpha01 (7 Oct 2026) adds optional heap profiling in a separate profiling pass and broader fixed-performance clock locking. It remains alpha; clock-lock support is primarily useful for Microbenchmark and is not a reason to change Nuvio's Macrobenchmark. Retain 1.5.0 as the comparison baseline.
- No code/configuration change was justified. No Gradle task/build, profile collection, device benchmark or CI was run.

References: [Teachmint startup case study](https://shubhamsinghbisht.com/articles/android-app-start-25-percent-faster/), [Grab R8/profile-guided optimization](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time), [AndroidX Benchmark release notes](https://developer.android.com/jetpack/androidx/releases/benchmark), [AndroidX CompilationMode](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/CompilationMode.kt).

## 17 November 2026 — R8 Analyzer as the next concrete optimization input

AGP 9.4.0 satisfies the AGP 9.3+ requirement for the R8 Configuration Analyzer. A normal minified benchmark build should produce `configanalyzer.html` alongside its mapping file. Nuvio's existing shrinker file contains several wide keep scopes (Supabase/Ktor, dynamic plugins, P2P/JNI, Media3/ExoPlayer and MPV), but their necessity cannot be judged from their breadth alone: reflection, generated serialization, runtime plugin loading, native method names and player integration can make some scopes contractually required.

The startup optimization decision is therefore to collect the reports for `fullBenchmarkRelease` and `playstoreBenchmarkRelease`, inspect each blocking rule and its origin, and then confirm runtime contracts before narrowing any keep rule. The benchmark workflow now preserves those reports, Gradle profile HTML, build logs and per-distribution elapsed timings as a separate diagnostic artifact. No ProGuard/R8 rule was modified and no runtime startup speedup is claimed.

References: [R8 Configuration Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer), [Grab R8/profile case study](https://developer.android.com/blog/posts/grab-r8-profile-guided-optimization-anr-startup-time), [Tinder keep-rule case study](https://developer.android.com/blog/posts/tinder-cuts-app-cold-starts-by-47-with-new-r8-configuration-analyzer).


### CI cache-key contract follow-up — 17 November 2026

The Rust compiler fingerprint is now emitted as a step output and referenced via `steps.rust-toolchain.outputs.cache-key`. GitHub's `env` expression context does not include arbitrary environment variables inherited from a previous step through `GITHUB_ENV`; using a step output avoids a silently empty compiler segment in the Cargo target cache key. The pinned `cargo-ndk` binary cache is independently keyed by its exact tool version. The bundle static contract now checks this distinction.

## External performance research follow-up — 18 November 2026

### Evidence reviewed

The live branch was inspected at `main-build-testing`; the earlier checkpoint commit remains `42e3dcdc4e6506f5c652e7040d4ce226b9f4372e`. Relative to the current `enhanced` branch, GitHub reports that `main-build-testing` is 133 commits ahead and 2,249 behind, with merge base `0ce28ba7e64b887f1666196490be4cad14d58462`. This is a divergent-history comparison, not the previous screenshot's 24-ahead/0-behind comparison-base metric. Keep the benchmark measurement baseline separate from source-branch ancestry.

Pinned versions on `main-build-testing`:
- Android Gradle Plugin 9.4.0
- AndroidX Macrobenchmark 1.5.0
- Coil 3.6.3
- Compose Multiplatform 1.13.0-beta01
- Kotlin 2.4.10
- kotlinx-coroutines 1.10.2

Context7 verified Coil 3.6.3 documentation for request sizing, Compose painter sizing and cache preloading. Android Developers' current docs and AndroidX release notes were checked separately. Exa was not used.

### 1. Baseline Profile distribution is a first-order performance variable

Community reports in r/Nuvio repeatedly compare noticeably smoother Play Store installs with sideloaded GitHub APKs:
- The 6 August post includes a side-by-side cold-start GUI comparison of Play Store and GitHub builds and was reported with 374 votes.
- The follow-up guide, updated 11 November, describes forcing ART compilation on the sideloaded Android TV build and reports smoother behavior afterward.

These are user reports, not controlled Android phone benchmarks, and the Reddit posters' explanations should not be treated as proof of the precise cause. However, Android's official documentation confirms that Baseline Profiles provide install-time optimization when delivered/installed correctly, and explicitly warns that non-Play distribution channels may not get equivalent install-time application; benefits may wait for background dexopt. Official verification is to inspect the built APK and confirm `assets/dexopt/baseline.prof` exists and is non-empty. Profile source text alone is not proof that ART received an installable profile.

Implication for our measurement:
- Keep the existing `CompilationMode.None()` vs `CompilationMode.Partial(BaselineProfileMode.Require)` cold-start A/B.
- Validate each actual benchmark APK's compiled profile and package identity before installing.
- Record installation path and ART compilation state; distinguish raw sideloaded first launch from a profile-installed/compiled install.
- Do not claim “beats official Nuvio” based only on a profile-required Macrobenchmark arm. Also measure the ordinary distribution path that a user actually installs.

References:
- https://www.reddit.com/r/Nuvio/comments/1vgyx6q/nuvio_github_apk_vs_google_playstore_gui/
- https://www.reddit.com/r/Nuvio/comments/1vw6vil/how_to_get_sideloadedgithub_nuvio_to_run_faster/
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles

### 2. Official Nuvio has since made performance-focused changes worth accounting for

The official Nuvio profile generator at upstream commit `81f836d40070ad96f17d8ff132fc7b72dfb4aef6` has separate Startup Profile and non-startup Baseline Profile collection passes. The non-startup pass exercises home scrolling, hero details, poster details and tab visits. This is a stronger coverage model than a startup-only journey; Enhanced's own current profile and benchmark sources must be compared directly before claiming profile-coverage superiority.

Upstream PR #2239 (`Optimize custom poster URL resolving performance`) explains a concrete Compose scrolling bottleneck:
- Regex compilation, temporary maps and repeated string replacement occurred per custom-poster URL resolution.
- The fix caches tokenized patterns and fills URLs with one StringBuilder pass.
- Continue Watching custom poster URLs are remembered to avoid repeat resolution during recomposition.
- The PR explicitly calls out allocation pressure, GC frequency, CPU spikes and UI stutter as the motivation; it reports manual output-equivalence checks, not a numeric benchmark.

Enhanced's `CustomPosterUrlResolver.kt` already contains the same class of optimisation: a synchronized bounded prepared-pattern cache, one-pass token scanning and StringBuilder replacement. Do not port the upstream PR blindly; first verify the Home/Continue Watching call-site memoization and test that list recomposition does not redo URL resolution. The resolver internals are already implemented in the inspected branch.

Upstream PR #2278 records separate fixes for binge reuse identity (filename, then infoHash fallback) and a bounded minimum lead time for next-episode prefetch. This is playback prefetch reliability rather than app cold-start performance, so keep it out of startup claims; it may inform a distinct player-responsiveness benchmark.

References:
- https://github.com/NuvioMedia/NuvioMobile/blob/81f836d40070ad96f17d8ff132fc7b72dfb4aef6/baselineprofile/src/main/kotlin/com/nuvio/baselineprofile/BaselineProfileGenerator.kt
- https://github.com/NuvioMedia/NuvioMobile/pull/2239
- https://github.com/NuvioMedia/NuvioMobile/pull/2278

### 3. Coil 3.6.3 audit: catalog prefetch is bounded; Home hero prefetch merits a trace

Enhanced's existing `PosterImagePrefetch.kt` explicitly computes the rendered poster dimensions (from its DpSize or card-shape width/aspect ratio) and passes a pixel `Size` to each upcoming catalogue request. This is a good existing optimization; do not replace it with custom `rememberAsyncImagePainter` calls or add a second prefetch layer without trace evidence.

In `HomeHeroSection.kt`, neighbor backdrop and logo prefetch requests are enqueued with `ImageRequest.Builder(context).data(url).build()` and no explicit size. Coil 3.6.3 documents that `rememberAsyncImagePainter` defaults to `Size.ORIGINAL` unless a constraints resolver is supplied; `AsyncImage` has its own constraints-aware sizing behavior. A request enqueued without a target does not inherit the displayed AsyncImage's measured constraints. This is a candidate for unnecessary original-size decode/cache memory, particularly for original-resolution backdrop URLs, but the exact decoded dimensions, cache-key reuse and runtime cost have not been measured in Nuvio.

Next evidence step:
1. Inspect `heroArtworkUrl` output variants and actual image source dimensions for the benchmark dataset.
2. Use a trace to count prefetch decode sizes, concurrent decodes, memory cache entries and time overlapping first interaction.
3. Compare current behavior against explicit bounds matched to actual hero view dimensions. Also compare disk-only preloading via Coil's documented `BlackholeDecoder` path, with memory cache disabled.
4. Retain whichever path improves first display / scroll smoothness without increasing network waits, repeat fetches or memory pressure. A disk-only preload is not automatically faster for the first visible image.

Coil 3.6.3 API evidence:
- `rememberConstraintsSizeResolver()` is intended when a custom painter needs on-screen constraints; it must be applied both to the ImageRequest and the Image modifier.
- `ImageLoader.enqueue(ImageRequest)` preloads to disk and memory cache by default.
- Coil's documented disk-only path disables memory cache and uses `BlackholeDecoder.Factory()` to avoid bitmap decoding; this is appropriate for warming disk cache only, not necessarily for a next-frame visible image.
- Current Coil 3.6.3 upgrade docs confirm that the painter defaults to `Size.ORIGINAL` and that Compose image APIs are restartable/skippable.

References:
- https://github.com/coil-kt/coil/blob/3.6.3/coil-compose/README.md
- https://github.com/coil-kt/coil/blob/3.6.3/docs/faq.md
- https://github.com/coil-kt/coil/blob/3.6.3/docs/upgrading_to_coil3.md

### 4. Composition tracing should be added to the measurement-only configuration

The Google `android/performance-samples` repository currently includes Macrobenchmark, Compose recomposition tracing and JankStats examples. Android Developers' current Macrobenchmark tracing instructions require:
- `androidx.compose.runtime:runtime-tracing` in the target app variant.
- `androidx.tracing:tracing-perfetto` and `tracing-perfetto-binary` in the Macrobenchmark test module, not the production app.
- `androidx.benchmark.fullTracing.enable=true` as the instrumentation argument. The old `perfettoSdkTracing` name is deprecated.

The existing project already retains Perfetto trace output for its startup A/B. Next step is to check whether Compose runtime tracing is present in the benchmark variant and the instrumentation arg is enabled; ordinary system traces alone do not guarantee named composable slices. If absent, add dependencies only to the benchmarkable app/test variants and preserve the production APK size boundary. Use the trace to locate high-cost Home rows, hero layers and first composition rather than assuming which component is slow.

References:
- https://github.com/android/performance-samples
- https://developer.android.com/develop/ui/compose/tooling/tracing
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args

### 5. Separate first frame, usable Home and browse/jank

The actual MainActivity source uses `ReportDrawnWhen { appReady.value }` and passes readiness from AppGate. This correctly makes TTFD more meaningful than a first-frame-only metric. The existing instrumentation also waits for a separate Home route marker and fully drawn marker. Preserve both; a route-mounted marker must not replace the app's readiness contract.

The next benchmark matrix should report:
- Cold TTID (first frame)
- Cold TTFD (visible and usable Home, with the existing fully-drawn signal)
- Home first-content readiness / first representative catalog poster only as separate supplementary milestones; do not redefine Android TTFD to wait for every remote catalog.
- Browse: fixed scroll distance through a deterministic, preloaded set of poster rows with frame timing/jank metrics.
- Navigation: fixed root-tab journey after warm-up, recording frame timing and transition latency.
- Startup compilation: no compilation, required Baseline Profile, partial/profile-required; full compilation retained only as a diagnostic.

Use the same APK signing/package, profile state, device, display refresh, network fixture, catalog data, account state, thermal/battery conditions and repeat count across arms. Report median and distribution, not a single run. Capture the profile output and Perfetto trace associated with every arm.

Official Android docs define TTID as first UI frame and TTFD as first frame after `reportFullyDrawn`; Compose `ReportDrawn` APIs should represent actual user readiness. Median cold-start measurements on a physical device are the recommended comparison.

References:
- https://developer.android.com/reference/kotlin/androidx/benchmark/macro/StartupTimingMetric
- https://developer.android.com/topic/performance/issues/launch-time
- https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- https://developer.android.com/codelabs/jetpack-compose-performance

### 6. Community reports: separate app performance from add-on/network latency

Public r/Nuvio and GitHub reports include complaints about slow poster/catalogue display, cold-start delay and sluggish UI. They are useful symptom reports, not controlled evidence that Nuvio's own renderer or networking is the cause:
- A mobile issue reports slow metadata/poster and catalog rows; maintainer response says catalogue/add-on metadata is upstream of Nuvio.
- A separate report says Cinemeta-fed posters lagged while another add-on loaded art quickly; it is anecdotal and tied to one provider.
- Android TV users compare Play Store and GitHub builds and report significant differences; platform/device and distribution-specific results cannot be generalized to Android phone results.

This reinforces a benchmark control requirement: fixed local or deterministic fixture content is required to isolate app rendering from third-party add-on response time. Run a second end-to-end benchmark with real catalogs after the isolated app benchmark, recording server/network wait separately.

References:
- https://github.com/NuvioMedia/NuvioMobile/issues/1486
- https://github.com/NuvioMedia/NuvioMobile/issues/1129
- https://www.reddit.com/r/Nuvio/comments/1wvzreb/android_tv_latest_update_takes_2030_seconds_stuck/

### 7. Current priority order

1. **Profile delivery audit:** inspect each actual full/playstore benchmark APK for non-empty `assets/dexopt/baseline.prof`; validate candidate profile paths and whether the user-facing distribution installs or applies it. Do not alter `mergeIntoMain=false` until variant output/package paths are proven.
2. **Benchmark trace coverage:** confirm runtime-tracing is included in the benchmark variant and fullTracing instrumentation arg is set; use named composable slices for the Home startup and scroll trace.
3. **Complete user-journey profile coverage:** the generator now adds a separate Home feed + catalogue shelf browse pass. Next, add hero/poster detail and root-tab journeys only when deterministic seeded content and selectors exist; keep each journey fail-fast.
4. **Image prefetch decode/cache parity:** bounded Home hero prefetch and display-matched shelf scale are implemented. Use Coil/Perfetto traces to verify memory-cache reuse, decoded pixel sizes and transition behavior before claiming a measurable improvement.
5. **Allocation/CPU audit:** inspect Continue Watching custom poster URL call-site memoization; resolver internals already cache tokens and use one-pass replacement.
6. **R8/startup DEX analysis:** inspect Configuration Analyzer output, Startup Profile rule composition and primary DEX contents before applying keep-rule changes. Avoid broad keep rules; measure the resulting binary and startup.
7. **Browse and navigation benchmark:** add measured frame timing/jank metrics for deterministic vertical scroll, horizontal shelves and root-tab journeys; Baseline Profile collection alone does not quantify frame performance.

### Research coverage and limitations

- GitHub: inspected Enhanced's branch source, existing benchmark research/phone notes, Baseline Profile configuration, Home hero, poster prefetch and custom poster URL resolver; inspected upstream Nuvio's profile generator and PRs #2239/#2278.
- Public social/forum search: found Reddit r/Nuvio reports relevant to APK distribution, startup and poster loading. Targeted X/Twitter searches did not return verifiable relevant posts in this pass. Do not claim X/Twitter investigation produced findings.
- Exa was unavailable during the earlier research snapshot but was restored on 18 November. This follow-up used Exa to cross-check Coil 3.6.3 image sizing/cache guidance, practitioner Baseline Profile journeys and current AndroidX profile/UIAutomator guidance.
- No production code, tests, build scripts, generated profiles, APKs, device runs or CI were changed or started in this research cycle. This document update is research-only. No Enhanced-vs-official speed superiority is claimed; it requires matched physical-device results.


## 18 November 2026 — improvement cycle 49: benchmark diagnostics and source contracts

### Startup Insights in both cold-start comparison arms

- Enabled AndroidX Benchmark Startup Insights for the existing `startupNoCompilation` and `startupWithBaselineProfile` tests using `ExperimentalConfig(null, new StartupInsightsConfig(true))`.
- Updated the Java `MacrobenchmarkRule.measureRepeated` call to the AndroidX 1.5.0 experimental-config overload. Its argument order is package, metrics, iterations, experimental config, compilation mode, startup mode, setup block and measure block.
- The measurement still emits only the two existing `StartupTimingMetric` result records and keeps ten cold iterations per arm. Startup Insights adds trace-based diagnostic insight; it does not add application runtime work or change the app's startup behavior.
- Kept `ArtMetric` deferred. The current result contract deliberately requires exactly the two timing arms and validates TTID/TTFD samples and traces. Optional ART fields need their own parser/report fixtures before they can safely join this run.

API evidence:
- AndroidX Benchmark 1.5.0 is the repository-pinned stable version.
- The public MacrobenchmarkRule API exposes the `ExperimentalConfig` overload with the parameter order now used by StartupBenchmark.
- Startup Insights is enabled through `StartupInsightsConfig` inside `ExperimentalConfig`.

### Regression-contract corrections

- Updated the readiness regression to assert the actual multiline Compose semantics block and the `testTag = "nuvio_startup_ready"` mapping used by MainActivity.
- Restricted the no-swallowed-selector-exception assertion to `StartupReadiness.markerVisible`. Best-effort diagnostic helpers may legitimately return an unavailable state; they do not turn marker-query failures into timeouts.
- Updated the metric-contract test to check Java's `CompilationMode.None.INSTANCE` interop and the actual `CompilationMode.Partial(BaselineProfileMode.Require)` construction.
- Updated the phone test to check the shared `StartupReadiness` helper instead of expecting diagnostic methods to be duplicated in both benchmark callers.

Verification boundary:
- Source was re-fetched from `main-build-testing` after the changes. The shell regression scripts were not executed because this environment has no mounted repository checkout.
- No Gradle task, APK build, device run, profile generation, Macrobenchmark or CI was started.
- This improves benchmark diagnosis and prevents stale preflight assertions; it is not an app startup speedup. No TTID/TTFD improvement is claimed.

References:
- https://developer.android.com/reference/androidx/benchmark/macro/junit4/MacrobenchmarkRule
- https://developer.android.com/reference/androidx/benchmark/ExperimentalConfig
- https://developer.android.com/jetpack/androidx/releases/benchmark

## 18 November 2026 — improvement cycle 50: protect authenticated API 33 benchmark state

- The phone runner now distinguishes profile collection/variant installation from authenticated Home measurement. On API 33, only `run` and `measure` require the shell to pass an already-rooted `id` or `su root id` check.
- This avoids starting Macrobenchmark on an unrooted API 33 device where AndroidX's pre-34 compilation reset may uninstall/reinstall the target app and erase the signed-in benchmark Home state.
- API 33 profile collection without root remains supported; API 34+ measurement remains available without root.
- Applied the equivalent capability check to the desktop ADB runner, whose combined workflow always proceeds from profile capture to authenticated measurement. API 28–33 require the root-capable shell; API 34+ remains the preferred non-rooted path.
- Extended the mocked phone-runner fixture to cover API 33 unrooted profile capture, unrooted measurement rejection before `am instrument`, successful measurement through `su root id`, and an already-root shell. Added static assertions for the desktop runner's matching guard.

This is a benchmark-state preservation and measurement-validity fix, not a runtime performance optimization.

Verification boundary:
- All changed files were re-fetched from the remote target branch and their expected guard/configuration fragments were confirmed.
- The mocked shell suite has not been executed in this environment; the new fixtures therefore remain pending execution. No build, Gradle task, CI workflow, physical-device measurement or profile collection was started.
- The current benchmark remains unauthorised for API 33 unrooted authenticated measurement. Use API 34+ for the normal unrooted path.
- No Nuvio-vs-official performance advantage is claimed. Equivalent device measurements are still required.

References:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/reference/androidx/benchmark/macro/junit4/MacrobenchmarkRule


## 18 November 2026 — improvement cycle 51: bound Home hero neighbour image prefetch

### Finding

The Home hero prefetch effect previously enqueued neighbour backdrop and logo requests using a bare `ImageRequest.Builder(context).data(url).build()`. Unlike the visible `AsyncImage`, this request did not inherit the image's measured Compose constraints.

Coil 3.6.3's defaults leave an un-sized `ImageRequest.Builder` at `Size.ORIGINAL`. The visible hero backdrop is measured to the Home hero viewport and rendered with `ContentScale.Crop`; the full-bleed logo is constrained to its content area and rendered with `ContentScale.Fit`.

### Change

- Added `HeroImagePrefetch.kt` with pure, allocation-light sizing and target-selection helpers.
- Backdrop prefetch requests now specify positive pixel dimensions derived from the measured hero viewport and map the existing crop rendering to Coil `Scale.FILL`.
- Full-bleed logo requests now specify pixel dimensions derived from the same content width fraction, content maximum width, logo width fraction and horizontal content/vertical-bar padding as the displayed logo. The 2.6:1 aspect ratio is used to derive logo height, and Coil `Scale.FIT` matches the visible content scale.
- Invalid/non-finite/zero bounds or blank URLs skip optional prefetch instead of falling back to an unbounded original-size request.
- The existing neighbour selection, prefetch timing, singleton image loader, metadata prefetch and displayed image composables are unchanged.

### Evidence

- Coil 3.6.3 documentation confirms `AsyncImage` determines image size from composable constraints, whereas `rememberAsyncImagePainter` defaults to original dimensions unless a resolver is supplied; the Coil request defaults also use `Size.ORIGINAL`.
- Coil 3.6.3 exposes `ImageRequest.Builder.size(width, height)` and `scale(Scale)`; `Scale.FILL` corresponds to crop/fill and `Scale.FIT` to fit behavior.
- The existing catalogue poster prefetcher already passes rendered pixel sizes and was deliberately left unchanged.

References:
- https://github.com/coil-kt/coil/blob/3.6.3/coil-compose/README.md
- https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/request/ImageRequest.kt
- https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/size/Scale.kt

## 18 November 2026 — improvement cycle 52: omit hidden card-hero logo prefetch

Card-style Home hero renders its poster/backdrop artwork but does not render the overlaid `HeroContentBlock` or its logo. The previous neighbour prefetch nevertheless queued each neighbour's logo URL in this layout.

- The pure target selector now emits a logo target only when the hero is full-bleed, the URL is non-blank and the computed pixel bounds are valid.
- The Compose layer avoids calculating logo bounds at all for card style. Backdrop artwork prefetch remains enabled.
- Full-bleed tablet and phone content use their corresponding vertical-bar/edge padding calculations; card style does not enter the logo path.
- Added commonTest cases for full-bleed crop/fit targets, card-style logo omission, aspect-ratio-derived logo dimensions, max-width/padding/fraction limits, narrow viewport, invalid dimensions and blank URLs.

### Validation boundary

- A local standalone `kotlinc` fixture compiled the fetched `HeroImagePrefetch.kt` helper source and executed eight behavioral assertions successfully.
- The repository's Compose Multiplatform `commonTest` suite was not run. No Gradle task, build, CI, emulator or physical-device benchmark was started.
- No memory, decode-time, transition-latency or user-perceived speedup is measured. The expected effect is to bound the neighbour backdrop/logo decode requests to useful rendered dimensions and avoid the hidden card-style logo request; Perfetto/Coil tracing and device comparison are still needed to quantify the impact.
- No changes were made to catalogue poster prefetch, visible hero image rendering, trailer behavior or metadata-fetch ordering. No Enhanced-vs-official performance advantage is claimed.


## 18 November 2026 — improvement cycle 53: match shelf prefetch scale to displayed artwork

### Finding

The shared `PosterRowImagePrefetchEffect` already requested rendered pixel dimensions, but it did not set Coil's scale. Coil's bare `ImageRequest.Builder` default is `Scale.FIT`. The visible Home catalogue cards use `ContentScale.Crop`, and the normal Continue Watching card/wide/poster artwork also uses Crop. Cloud-library artwork is the exception in wide and poster layouts, where the visible composable explicitly uses Fit; the card layout still uses Crop.

For Coil 3.6.3, `AsyncImage` maps `ContentScale.Fit` / `Inside` to `Scale.FIT` and other content scales such as Crop to `Scale.FILL`. The size/scale pair affects the decoded result and whether a prefetched memory-cache entry is suitable for the visible request. A generic prefetch Fit request can therefore produce an image size that does not match the Crop display request.

### Change

- Added `PosterPrefetchScale` to carry display scale with each prefetch target.
- Shelf prefetch now maps Crop targets to Coil `Scale.FILL` and Fit targets to Coil `Scale.FIT`.
- Home catalogue targets retain Crop. Continue Watching target creation selects Crop for card style and all non-cloud artwork, and Fit for cloud-library wide/poster layouts only.
- Preserved the existing fallback URL cache-key extras, rendered pixel sizing, prefetch window, queue-ahead count and skip count.
- Kept the new scale field after the existing optional `sizeDp` property to preserve the data class's previous positional constructor shape.

### Tests and evidence

- Added focused commonTest cases for catalogue Crop, normal Continue Watching Crop, cloud card Crop and cloud wide/poster Fit.
- A standalone `kotlinc` fixture compiled the exact pure scale helper and passed four behavior cases. The Compose commonTest suite itself remains unexecuted.
- Coil 3.6.3 source maps Compose content scale to `Scale.FILL` / `Scale.FIT`; its `Precision.INEXACT` documentation confirms that a sufficiently large cached image can be reused by a smaller AsyncImage request. We did not force `Precision.INEXACT` on prefetch requests; the change addresses the proven scale mismatch without changing precision policy.

References:
- https://github.com/coil-kt/coil/blob/3.6.3/coil-compose-core/src/commonMain/kotlin/coil3/compose/internal/utils.kt
- https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/request/ImageRequest.kt
- https://coil-kt.github.io/coil/api/coil-core/coil3.size/-precision/-i-n-e-x-a-c-t/index.html
- https://github.com/coil-kt/coil/issues/2766

### Verification boundary

- Remote source/test files were re-fetched. Four local helper assertions passed.
- The repository's commonTest suite was not run. No Gradle task, APK build, CI, emulator or device test was started.
- No cache hit-rate, decode-time, memory or scroll-jank measurement is available yet. The likely benefit is better prefetch/display decode parity and less avoidable reloading, but it requires Coil/Perfetto traces to measure.
- No app visible rendering or content scale was changed; only the prefetch request matches the existing visible scale.


## 18 November 2026 — improvement cycle 54: extend Baseline Profile coverage to Home browsing

### Finding

The branch's `BaselineProfileGenerator` only collected the launcher startup journey. Android's Baseline Profile guidance recommends covering important in-app journeys as well, including scrolling lists. The app already exposes `testTagsAsResourceId` and a Home-ready marker, so it can use Compose test tags as stable UI Automator selectors without adding debug-only code paths.

### Change

- Added `nuvio_benchmark_home_scroll` to the Home vertical `NuvioScreen` LazyColumn and `nuvio_benchmark_catalog_row` to Home catalogue shelf LazyRows.
- Added a separate `homeBrowseJourneys` Baseline Profile collection pass. It launches the signed-in benchmark app, waits for the existing Home + fully-drawn markers, scrolls the Home feed until a catalogue shelf is found, scrolls that shelf in both horizontal directions, then returns the Home feed upward.
- Set the new collection to `includeInStartupProfile=false`. The existing `startup` pass remains the only Startup Profile input; `home_browse` contributes only Baseline Profile rules. Existing `mergeIntoMain=false`, `saveInSrc=true`, flavor-specific generation and manual profile workflow settings are unchanged.
- Added bounded, condition-based `StartupReadiness.scrollUntilMarker` and fail-fast `scrollRequired` helpers. They verify that selectors resolve to scrollable nodes and that actual scroll gestures succeed; a missing or non-scrollable shelf fails with diagnostics rather than silently generating incomplete coverage.
- Updated the startup-readiness regression contract to require both Compose selectors, the browse profile test, the non-Startup-Profile flag and the scroll-helper checks.

### Research and API evidence

- Android's guidance recommends Baseline Profiles for important journeys in addition to startup, and its debug guide calls out covering important user journeys: https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles
- `BaselineProfileRule.collect` documents `outputFilePrefix` per critical journey and `includeInStartupProfile` to restrict Startup Profile inclusion to startup flows: https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule
- Repository pins UI Automator 2.4.1. The official release notes confirm that version and the 2.4 API series: https://developer.android.com/jetpack/androidx/releases/test-uiautomator
- The Home browse profile requires a signed-in benchmark account and at least one populated, horizontally scrollable catalogue. This is deliberately explicit in the generator documentation; a blank account must not produce a false-success profile.

### Verification boundary

- Remote Home, catalogue, profile generator, readiness helper and regression script were re-fetched after the changes. Source-level assertions confirm both markers and the Baseline Profile-only collection wiring.
- The pure scale helper's four local cases passed; the repo's Android instrumentation/Compose test suites were not run.
- No Gradle task, Baseline Profile generation, APK build, CI, device interaction or Macrobenchmark was started. The new browse journey still needs a supported physical device and a seeded signed-in benchmark catalogue before the generated profile can be validated.
- This adds code paths to the profile collection source; it does not itself change a user's installed Baseline Profile until the profile is generated, reviewed and packaged. No runtime performance advantage is claimed yet.
