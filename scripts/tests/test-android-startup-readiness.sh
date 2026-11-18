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
  if [ "$source" != "$READINESS_SOURCE" ]; then
  grep -Fq 'By.res("nuvio_benchmark_home")' "$source" || {
    echo "FAIL: benchmark must first wait for the HomeScreen marker." >&2
    exit 1
  }
  grep -Fq 'By.res("nuvio_startup_ready")' "$source" || {
    echo "FAIL: benchmark must also wait for the fully-drawn marker to preserve TTFD coverage." >&2
    exit 1
  }
  for marker in nuvio_appgate_main nuvio_appgate_profile_loading nuvio_main_content_loading nuvio_launch_overlay_transitioning nuvio_main_content_ready nuvio_readiness_home_ready_profile_switch_idle nuvio_readiness_home_ready_profile_switch_loading nuvio_readiness_home_pending_profile_switch_idle nuvio_readiness_home_pending_profile_switch_loading; do
    grep -Fq "markerVisible(device, \"$marker\")" "$source" || {
      echo "FAIL: readiness diagnostics must report $marker." >&2
      exit 1
    }
  done
  grep -Fq 'SystemClock.elapsedRealtime()' "$source" || {
    echo "FAIL: both startup markers must share one elapsed-realtime deadline." >&2
    exit 1
  }
  grep -Fq 'remainingMs' "$source" || {
    echo "FAIL: fully-drawn wait must use the remaining shared timeout." >&2
    exit 1
  }
  grep -Fq 'markerVisible(UiDevice device, String marker)' "$source" || {
    echo "FAIL: readiness diagnostics must report marker visibility safely." >&2
    exit 1
  }
  fi
  if grep -Fq 'dumpWindowHierarchy' "$source"; then
    echo "FAIL: diagnostics must not serialize the entire accessibility hierarchy." >&2
    exit 1
  fi
done

grep -Fq 'device.findWindow(By.Window.active(true))' "$READINESS_SOURCE" || {
  echo "FAIL: foreground recovery must use UIAutomator's active-window API." >&2
  exit 1
}
grep -Fq 'Active window package:' "$READINESS_SOURCE" || {
  echo "FAIL: readiness diagnostics must report the active-window package separately." >&2
  exit 1
}
grep -Fq 'Last package to report an accessibility event:' "$READINESS_SOURCE" || {
  echo "FAIL: diagnostics must retain the last accessibility-event package with its documented meaning." >&2
  exit 1
}
printf 'PASS: both benchmark journeys wait for Home and fully-drawn markers; shared helper diagnostics use active-window semantics.\n'