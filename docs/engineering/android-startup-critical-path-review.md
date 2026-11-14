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

The Android app included AndroidX ProfileInstaller 1.4.1, but the app-owned `androidApp/src/main/baseline-prof.txt` was empty. There was no Baseline Profile producer module and no Macrobenchmark collection journey. ProfileInstaller installs profile data packaged with an app; it does not generate Nuvio-specific profile rules.

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
