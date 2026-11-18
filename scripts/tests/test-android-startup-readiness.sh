#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
MAIN_ACTIVITY_SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt"
HOME_SCREEN_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt"
APP_GATE_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt"
MAIN_CONTENT_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"

for source in "$MAIN_ACTIVITY_SOURCE" "$HOME_SCREEN_SOURCE" "$APP_GATE_SOURCE" "$MAIN_CONTENT_SOURCE" "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
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
  grep -Fq 'HomeScreen wait elapsed:' "$source" || {
    echo "FAIL: readiness failures must report how long the HomeScreen wait consumed." >&2
    exit 1
  }
  grep -Fq 'Remaining shared deadline when fully-drawn wait began:' "$source" || {
    echo "FAIL: readiness failures must report the remaining shared deadline." >&2
    exit 1
  }
  grep -Fq 'getUiAutomation()' "$source" || {
    echo "FAIL: readiness failures must capture actual system window/activity state." >&2
    exit 1
  }
  grep -Fq 'dumpsys activity activities' "$source" || {
    echo "FAIL: failure diagnostics must include the resumed activity." >&2
    exit 1
  }
  grep -Fq 'dumpsys window windows' "$source" || {
    echo "FAIL: failure diagnostics must include the focused window." >&2
    exit 1
  }
  grep -Fq 'pidof com.nuvio.media.benchmark' "$source" || {
    echo "FAIL: readiness diagnostics must report whether the target process still exists." >&2
    exit 1
  }
  grep -Fq 'markerVisible(UiDevice device, String marker)' "$source" || {
    echo "FAIL: readiness diagnostics must report marker visibility safely." >&2
    exit 1
  }
  grep -Fq 'Last package to report an accessibility event' "$source" || {
    echo "FAIL: diagnostics must not mislabel the last accessibility-event package as foreground." >&2
    exit 1
  }
  if grep -Fq 'dumpWindowHierarchy' "$source"; then
    echo "FAIL: diagnostics must not serialize the entire accessibility hierarchy." >&2
    exit 1
  fi
done

printf 'PASS: both benchmark journeys preserve Home/TTFD waits and capture timing plus failure-time device state.\n'