#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
MAIN_ACTIVITY_SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt"
HOME_SCREEN_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt"
APP_GATE_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt"
MAIN_CONTENT_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
READINESS_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupReadiness.java"

for source in "$MAIN_ACTIVITY_SOURCE" "$HOME_SCREEN_SOURCE" "$APP_GATE_SOURCE" "$MAIN_CONTENT_SOURCE" "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE" "$READINESS_SOURCE"; do
  [ -f "$source" ] || {
    echo "FAIL: startup readiness source not found: $source" >&2
    exit 1
  }
done

# The app root enables resource-ID mapping; the Home screen owns an independent,
# unconditional test tag. Readiness must not depend on MainActivity's appReady callback.
grep -Fq '.semantics { testTagsAsResourceId = true }' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: Android Compose root must expose test tags as resource IDs." >&2
  exit 1
}
grep -Fq 'ReportDrawnWhen { appReady.value }' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: Macrobenchmark TTFD reporting must remain tied to the app's fully-drawn signal." >&2
  exit 1
}
grep -Fq 'Modifier.testTag("nuvio_startup_ready")' "$MAIN_ACTIVITY_SOURCE" || {
  echo "FAIL: the fully-drawn signal must remain externally observable for benchmark validation." >&2
  exit 1
}
grep -Fq 'BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("nuvio_benchmark_home"))' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: HomeScreen root must expose the benchmark Home marker." >&2
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
grep -Fq 'nuvio_readiness_home_' "$MAIN_CONTENT_SOURCE" || {
  echo "FAIL: MainAppContent must expose the initial-home/profile-switch readiness combination." >&2
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

# Shared readiness implementation owns marker discovery, deadline handling,
# diagnostics and foreground recovery. Test it directly.
grep -Fq 'static boolean markerVisible(UiDevice device, String marker)' "$READINESS_SOURCE" || {
  echo "FAIL: shared readiness helper must expose safe marker lookup." >&2
  exit 1
}
grep -Fq 'By.res(TARGET_APP_PACKAGE, marker)' "$READINESS_SOURCE" || {
  echo "FAIL: marker lookup must scope resource IDs to the benchmark app package." >&2
  exit 1
}
grep -Fq 'device.findWindow(By.Window.active(true))' "$READINESS_SOURCE" || {
  echo "FAIL: foreground recovery must use UIAutomator's active-window API." >&2
  exit 1
}
grep -Fq 'Active window package:' "$READINESS_SOURCE" || {
  echo "FAIL: diagnostics must report the active-window package." >&2
  exit 1
}
grep -Fq 'Last package to report an accessibility event:' "$READINESS_SOURCE" || {
  echo "FAIL: diagnostics must label the last accessibility-event package accurately." >&2
  exit 1
}
grep -Fq 'private static boolean isKnownShellPackage(String packageName)' "$READINESS_SOURCE" || {
  echo "FAIL: known-shell package matching must be centralized." >&2
  exit 1
}
grep -Fq 'SystemClock.elapsedRealtime()' "$READINESS_SOURCE" || {
  echo "FAIL: readiness waits must use monotonic elapsed-realtime deadlines." >&2
  exit 1
}
grep -Fq 'remaining' "$READINESS_SOURCE" || {
  echo "FAIL: Home and fully-drawn waits must share one total timeout budget." >&2
  exit 1
}

printf 'PASS: both benchmark journeys wait for Home and fully-drawn markers; shared helper diagnostics use active-window semantics.\n'