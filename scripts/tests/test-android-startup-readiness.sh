#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
MAIN_ACTIVITY_SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt"
HOME_SCREEN_SOURCE="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"

for source in "$MAIN_ACTIVITY_SOURCE" "$HOME_SCREEN_SOURCE" "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
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
grep -Fq 'BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("nuvio_benchmark_home"))' "$HOME_SCREEN_SOURCE" || {
  echo "FAIL: HomeScreen root must expose the benchmark Home marker." >&2
  exit 1
}
for source in "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
  grep -Fq 'By.res("nuvio_benchmark_home")' "$source" || {
    echo "FAIL: benchmark must wait for the HomeScreen marker, not Activity appReady." >&2
    exit 1
  }
  if grep -Fq 'By.res("nuvio_startup_ready")' "$source"; then
    echo "FAIL: benchmark still depends on the Activity-level readiness callback." >&2
    exit 1
  fi
done

printf 'PASS: both benchmark journeys wait for the actual HomeScreen composable; TTFD remains reported by ReportDrawnWhen.\n'
