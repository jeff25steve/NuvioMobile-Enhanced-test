#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
MAIN_ACTIVITY_SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt"
HOME_SCREEN_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt"
HOME_CATALOG_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeCatalogSection.kt"
APP_GATE_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt"
MAIN_CONTENT_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt"
APP_READINESS_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/AppReadiness.kt"
HOME_MODELS_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeModels.kt"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
HOME_BROWSE_READINESS_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/HomeBrowseReadiness.java"
ANDROID_BUILD_SOURCE="$ROOT_DIR/androidApp/build.gradle.kts"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
READINESS_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupReadiness.java"

for source in "$MAIN_ACTIVITY_SOURCE" "$HOME_SCREEN_SOURCE" "$HOME_CATALOG_SOURCE" "$APP_GATE_SOURCE" "$MAIN_CONTENT_SOURCE" "$APP_READINESS_SOURCE" "$HOME_MODELS_SOURCE" "$GENERATOR_SOURCE" "$HOME_BROWSE_READINESS_SOURCE" "$BENCHMARK_SOURCE" "$READINESS_SOURCE"; do
  [ -f "$source" ] || {
    echo "FAIL: startup readiness source not found: $source" >&2
    exit 1
  }
done

# The app root enables resource-ID mapping; the Home screen owns an independent,
# unconditional test tag. Readiness must not depend on MainActivity's appReady callback.
grep -Fq '.semantics {' "$MAIN_ACTIVITY_SOURCE" &&
  grep -Fq 'testTagsAsResourceId = true' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: Android Compose root must expose test tags as resource IDs." >&2
  exit 1
}
grep -Fq 'ReportDrawnWhen { appReady.value }' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: Macrobenchmark TTFD reporting must remain tied to the app's fully-drawn signal." >&2
  exit 1
}
grep -Fq 'testTag = "nuvio_startup_ready"' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: the fully-drawn signal must remain externally observable for benchmark validation." >&2
  exit 1
}
grep -Fq 'BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("nuvio_benchmark_home"))' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: HomeScreen root must expose the benchmark Home marker." >&2
  exit 1
}
grep -Fq '.testTag("nuvio_benchmark_home_scroll")' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: HomeScreen vertical LazyColumn must expose a stable scroll selector." >&2
  exit 1
}
grep -Fq 'rowModifier = Modifier.testTag("nuvio_benchmark_catalog_row")' "$HOME_CATALOG_SOURCE" || {
  echo "FAIL: Home catalogue shelves must expose a stable horizontal scroll selector." >&2
  exit 1
}
grep -Fq '"nuvio_appgate_main"' "$APP_GATE_SOURCE" || {
  echo "FAIL: AppGate must expose its main-state diagnostic marker." >&2
  exit 1
}
grep -Fq '"nuvio_appgate_profile_loading"' "$APP_GATE_SOURCE" || {
  echo "FAIL: AppGate must expose profile-selection loading state." >&2
  exit 1
}
grep -Fq '"nuvio_main_content_loading"' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: MainAppContent must expose its pending readiness state." >&2
  exit 1
}
grep -Fq '"nuvio_launch_overlay_transitioning"' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: MainAppContent must expose its launch-overlay transition state." >&2
  exit 1
}
grep -Fq '"nuvio_main_content_ready"' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: MainAppContent must expose its ready state." >&2
  exit 1
}
grep -Fq 'stateDescription =' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: the visible main-content root must expose its readiness state." >&2
  exit 1
}
# The launch-overlay timeout may reveal the Home UI, but must never promote appReady.
grep -Fq 'initialHomeFallbackElapsed = true' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: MainAppContent must keep a separate visual overlay fallback state." >&2
  exit 1
}
grep -Fq 'shouldShowMainLaunchOverlay(' "$MAIN_CONTENT_SOURCE" && \
  grep -Fq 'shouldReportMainContentReady(' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: launch-overlay visibility and Activity fully-drawn reporting must use separate predicates." >&2
  exit 1
}
timeout_context="$(grep -B 8 -A 5 -F 'initialHomeFallbackElapsed = true' "$MAIN_CONTENT_SOURCE")"
if printf '%s\n' "$timeout_context" | grep -Fq 'initialHomeReady = true'; then
  echo "FAIL: timeout fallback must not set product Home readiness." >&2
  exit 1
fi
grep -Fq 'withFrameNanos { }' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: Home layout readiness must wait for a Compose frame after the loading-state transition." >&2
  exit 1
}
grep -Fq 'onInitialHomeContentRendered = { initialHomeReady = true }' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: the product Home-ready state must be updated by the Home content callback." >&2
  exit 1
}
grep -Fq 'rememberUpdatedState(onRootContentReady)' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: the root readiness effect must use the latest callback without restarting on lambda identity changes." >&2
  exit 1
}
grep -Fq 'onRootContentReady != null,' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: root readiness must restart only when callback availability changes, not callback identity." >&2
  exit 1
}
grep -Fq 'rememberUpdatedState(onFirstCatalogRendered)' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: Home readiness must use the latest callback without restarting on lambda identity changes." >&2
  exit 1
}

# MainActivity must retain its complete Android lifecycle/intent integration.
for activity_method in 'override fun onCreate(' 'override fun onNewIntent(' 'override fun onUserLeaveHint(' 'override fun onPictureInPictureModeChanged(' 'override fun onDestroy('; do
  grep -Fq "$activity_method" "$MAIN_ACTIVITY_SOURCE" || {
    echo "FAIL: MainActivity is missing restored lifecycle method: $activity_method" >&2
    exit 1
  }
done
grep -Fq 'snapshotFlow {' "$HOME_SCREEN_SOURCE" && \
  grep -Fq 'homeListState.layoutInfo.visibleItemsInfo' "$HOME_SCREEN_SOURCE" && \
  grep -Fq 'isInitialHomeContentLoading' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: Home readiness must wait for a laid-out visible list item after initial loading resolves." >&2
  exit 1
}
grep -Fq 'item.key != "home_hero"' "$HOME_SCREEN_SOURCE" && \
  grep -Fq 'homeUiState.heroItems.isNotEmpty()' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: Home readiness must not count a hero skeleton/reserved slot as real content." >&2
  exit 1
}
grep -Fq 'homeSourcesLoaded = serversUiState.revision > 0' "$HOME_SCREEN_SOURCE" && \
  grep -Fq 'homeSourcesLoaded' "$HOME_MODELS_SOURCE" || {
  echo "FAIL: empty Home must not report readiness before initial source configuration has loaded." >&2
  exit 1
}
# Only the launcher startup journey belongs in the Startup Profile.
grep -Fq 'StartupReadiness.TARGET_APP_PACKAGE' "$GENERATOR_SOURCE" || {
  echo "FAIL: Baseline Profile generator must target the isolated benchmark app." >&2
  exit 1
}
grep -Fq 'true,' "$GENERATOR_SOURCE" || {
  echo "FAIL: Startup Profile collection must remain enabled." >&2
  exit 1
}
grep -Fq '"startup"' "$GENERATOR_SOURCE" || {
  echo "FAIL: startup profile output prefix must remain explicit." >&2
  exit 1
}
# Browse-only collection complements the startup-only rule and must not enter Startup Profile.
grep -Fq 'public void homeBrowseJourneys()' "$GENERATOR_SOURCE" || {
  echo "FAIL: Baseline Profile generator must include the Home browse journey." >&2
  exit 1
}
grep -Fq '"home_browse"' "$GENERATOR_SOURCE" || {
  echo "FAIL: Home browse profile output prefix must remain explicit." >&2
  exit 1
}
grep -Fq '"home_browse", false, true' "$GENERATOR_SOURCE" || {
  echo "FAIL: Home browse methods must be Baseline Profile rules only, not Startup Profile rules." >&2
  exit 1
}
grep -Fq 'nuvio_benchmark_home_scroll' "$GENERATOR_SOURCE" &&
  grep -Fq 'nuvio_benchmark_catalog_row' "$GENERATOR_SOURCE" || {
  echo "FAIL: Home browse profile must scroll both the vertical feed and a horizontal catalogue shelf." >&2
  exit 1
}
# Both cold-start arms now collect the AndroidX ART diagnostics as well as startup timing.
[ "$(grep -Fc 'new ArtMetric()' "$BENCHMARK_SOURCE")" -eq 2 ] || {
  echo "FAIL: both cold-start arms must collect ART runtime diagnostics." >&2
  exit 1
}
grep -Fq 'new StartupTimingMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both cold-start arms must continue to collect StartupTimingMetric." >&2
  exit 1
}
# Release/benchmark APK needs R8 optimization; the capture build stays non-minified.
grep -Fq 'isMinifyEnabled = true' "$ANDROID_BUILD_SOURCE" || {
  echo "FAIL: optimized benchmarkRelease must keep R8 enabled." >&2
  exit 1
}
grep -Fq 'initWith(getByName("release"))' "$ANDROID_BUILD_SOURCE" || {
  echo "FAIL: benchmarkRelease must inherit the optimized release configuration." >&2
  exit 1
}
for source in "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
  grep -Fq 'StartupReadiness.awaitHomeAndFullyDrawn(device)' "$source" || {
    echo "FAIL: both benchmark journeys must delegate to the shared Home/fully-drawn readiness wait." >&2
    exit 1
  }
  if grep -Fq 'dumpWindowHierarchy' "$source"; then
    echo "FAIL: benchmark callers must not serialize the entire accessibility hierarchy." >&2
    exit 1
  fi
done

# Startup readiness accepts only marker nodes owned by the target app in its active window.
grep -Fq 'static UiObject2 targetMarker(UiDevice device, String marker)' "$READINESS_SOURCE" || {
  echo "FAIL: shared readiness helper must expose active-window, package-owned marker lookup." >&2
  exit 1
}
grep -Fq 'device.findWindow(By.Window.active(true))' "$READINESS_SOURCE" || {
  echo "FAIL: marker lookup must start from UI Automator's active accessibility window." >&2
  exit 1
}
grep -Fq 'activeWindow.findObject(composeTagSelector(marker))' "$READINESS_SOURCE" || {
  echo "FAIL: marker lookup must be scoped to the active target window, not global hierarchy." >&2
  exit 1
}
grep -Fq 'static BySelector composeTagSelector(String marker)' "$READINESS_SOURCE" && \
  grep -Fq 'return By.res(marker);' "$READINESS_SOURCE" || {
  echo "FAIL: Compose testTagsAsResourceId selectors must use the bare testTag resource name." >&2
  exit 1
}
if grep -Fq 'return By.res(TARGET_APP_PACKAGE, marker);' "$READINESS_SOURCE"; then
  echo "FAIL: package-qualified By.res does not match Compose testTag resource names." >&2
  exit 1
fi
grep -Fq 'object.getApplicationPackage()' "$READINESS_SOURCE" || {
  echo "FAIL: marker ownership must be verified on the matched UI node itself." >&2
  exit 1
}
if grep -Fq 'monkey -p' "$READINESS_SOURCE"; then
  echo "FAIL: readiness waits must not relaunch or re-foreground the app during a measurement." >&2
  exit 1
fi
grep -Fq 'waitForTargetAppActive' "$READINESS_SOURCE" || {
  echo "FAIL: benchmark readiness must require the target app to own the active window." >&2
  exit 1
}
grep -Fq 'FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS = 15_000L' "$READINESS_SOURCE" || {
  echo "FAIL: fully-drawn readiness must have a short, separate post-Home timeout." >&2
  exit 1
}
grep -Fq 'HOME_STARTUP_TIMEOUT_MS = 60_000L' "$READINESS_SOURCE" || {
  echo "FAIL: reaching the Home screen must retain a reasonable cold-start budget." >&2
  exit 1
}
grep -Fq 'HomeScreen is visible, but the product fully-drawn marker' "$READINESS_SOURCE" || {
  echo "FAIL: post-Home readiness failures must be distinguished from launch timeouts." >&2
  exit 1
}
grep -Fq 'markerQueryFailure(' "$READINESS_SOURCE" && \
  grep -Fq 'failure.initCause(cause)' "$READINESS_SOURCE" || {
  echo "FAIL: accessibility query failures must retain their original causes." >&2
  exit 1
}
grep -Fq 'rootReadinessStateDescription' "$READINESS_SOURCE" || {
  echo "FAIL: readiness diagnostics must report the root state description." >&2
  exit 1
}
grep -Fq 'Active window package:' "$READINESS_SOURCE" && \
  grep -Fq 'Last package to report an accessibility event:' "$READINESS_SOURCE" || {
  echo "FAIL: diagnostics must distinguish the active window from the last accessibility event." >&2
  exit 1
}

# Home browse scrolling is a distinct responsibility from startup readiness waits.
grep -Fq 'static void scrollUntilMarker(' "$HOME_BROWSE_READINESS_SOURCE" && \
  grep -Fq 'static void scrollRequired(' "$HOME_BROWSE_READINESS_SOURCE" || {
  echo "FAIL: Home browse scrolling must live in its dedicated readiness helper." >&2
  exit 1
}
grep -Fq 'StartupReadiness.targetMarker(device, marker)' "$HOME_BROWSE_READINESS_SOURCE" && \
  grep -Fq 'scrollable.isScrollable()' "$HOME_BROWSE_READINESS_SOURCE" && \
  grep -Fq 'scrollable.scroll(direction, SCROLL_PERCENT)' "$HOME_BROWSE_READINESS_SOURCE" || {
  echo "FAIL: Home browse must re-resolve package-owned scroll nodes and verify scrollability." >&2
  exit 1
}
grep -Fq 'markerHasVisibleBounds(device, targetMarker)' "$HOME_BROWSE_READINESS_SOURCE" && \
  grep -Fq 'object.getVisibleBounds()' "$HOME_BROWSE_READINESS_SOURCE" || {
  echo "FAIL: Home shelf discovery must wait until the target row has visible screen bounds." >&2
  exit 1
}
grep -Fq 'HomeBrowseReadiness.scrollRequired(' "$GENERATOR_SOURCE" && \
  grep -Fq 'HomeBrowseReadiness.scrollUntilMarker(' "$GENERATOR_SOURCE" || {
  echo "FAIL: the Home browse profile must use the dedicated browse readiness helper." >&2
  exit 1
}
grep -Fq 'Direction.DOWN' "$GENERATOR_SOURCE" && grep -Fq 'Direction.UP' "$GENERATOR_SOURCE" && \
  grep -Fq 'Direction.RIGHT' "$GENERATOR_SOURCE" && grep -Fq 'Direction.LEFT' "$GENERATOR_SOURCE" || {
  echo "FAIL: Home browse profile must exercise vertical and horizontal scroll in both directions." >&2
  exit 1
}

grep -Fq 'write_run_provenance' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh" || {
  echo "FAIL: each benchmark run must save runner and APK provenance." >&2
  exit 1
}
grep -Fq 'write_run_provenance' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh" || {
  echo "FAIL: provenance capture must be integrated into run_instrumentation." >&2
  exit 1
}
grep -Fq 'focus_state_file="$RUN_DIR/wireless-debug-focus-check.txt"' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh" || {
  echo "FAIL: Wireless debugging state must be captured in a file, not a large shell variable." >&2
  exit 1
}
if grep -Fq 'focus_state="$(dumpsys window windows' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh"; then
  echo "FAIL: runner must not keep combined dumpsys output in a shell variable." >&2
  exit 1
fi
if grep -Fq 'printf '\''Runner path: %s\\\\n'\''' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh"; then
  echo "FAIL: provenance records must use real newline characters, not literal backslash-n text." >&2
  exit 1
fi
grep -Fq 'printf '\''App APK SHA-256: %s\\n'\''' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh" || {
  echo "FAIL: provenance must record the application APK hash." >&2
  exit 1
}
grep -Fq 'printf '\''Test APK SHA-256: %s\\n'\''' "$ROOT_DIR/scripts/nuvio-phone-benchmark.sh" || {
  echo "FAIL: provenance must record the instrumentation APK hash." >&2
  exit 1
}
printf 'PASS: readiness selectors, visible-root diagnostics, provenance capture and file-backed dumpsys checks are guarded.\n'