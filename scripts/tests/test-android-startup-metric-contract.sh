#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
DESKTOP_RUNNER="$ROOT_DIR/scripts/run-android-startup-profile-benchmark.sh"
PHONE_RUNNER="$ROOT_DIR/scripts/nuvio-phone-benchmark.sh"
WORKFLOW_SOURCE="$ROOT_DIR/.github/workflows/android-startup-profile-benchmark.yml"

[ -f "$BENCHMARK_SOURCE" ] || {
  echo "FAIL: StartupBenchmark source not found: $BENCHMARK_SOURCE" >&2
  exit 1
}
[ -f "$DESKTOP_RUNNER" ] || {
  echo "FAIL: desktop benchmark runner not found: $DESKTOP_RUNNER" >&2
  exit 1
}
[ -f "$PHONE_RUNNER" ] || {
  echo "FAIL: phone benchmark runner not found: $PHONE_RUNNER" >&2
  exit 1
}
[ -f "$WORKFLOW_SOURCE" ] || {
  echo "FAIL: benchmark workflow not found: $WORKFLOW_SOURCE" >&2
  exit 1
}

awk '
  /^[[:space:]]*@Test[[:space:]]*$/ { annotated=1; next }
  /^[[:space:]]*public void startupNoCompilation[[:space:]]*\(/ && annotated { found=1 }
  { if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*@Test[[:space:]]*$/) annotated=0 }
  END { exit !found }
' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare a JUnit test for the no-compilation baseline." >&2
  exit 1
}
grep -Fq 'runColdStartupBenchmark(' "$BENCHMARK_SOURCE" &&
  grep -Fq 'CompilationMode.None.INSTANCE' "$BENCHMARK_SOURCE" || {
  echo "FAIL: no-compilation test must execute the shared cold-start measurement with Java's None singleton." >&2
  exit 1
}
awk '
  /^[[:space:]]*@Test[[:space:]]*$/ { annotated=1; next }
  /^[[:space:]]*public void startupWithBaselineProfile[[:space:]]*\(/ && annotated { found=1 }
  { if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*@Test[[:space:]]*$/) annotated=0 }
  END { exit !found }
' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare a JUnit test for the packaged Baseline Profile." >&2
  exit 1
}
grep -Fq 'new CompilationMode.Partial(BaselineProfileMode.Require)' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Baseline Profile test must require the packaged profile during partial compilation." >&2
  exit 1
}
grep -Fq 'new StartupTimingMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both comparison arms must collect StartupTimingMetric." >&2
  exit 1
}
grep -Fq 'new ArtMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both comparison arms must collect ART runtime diagnostics." >&2
  exit 1
}
[ "$(grep -Fc 'new ArtMetric()' "$BENCHMARK_SOURCE")" -eq 2 ] || {
  echo "FAIL: ART diagnostics must be collected in both cold-start comparison arms." >&2
  exit 1
}
grep -Fq 'StartupMode.COLD' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both comparison arms must measure cold starts." >&2
  exit 1
}
grep -Fq 'private static final int ITERATIONS = 10;' "$BENCHMARK_SOURCE" || {
  echo "FAIL: startup comparison must retain ten measured iterations per arm." >&2
  exit 1
}

grep -Fq 'for metric in timeToInitialDisplayMs timeToFullDisplayMs; do' "$DESKTOP_RUNNER" || {
  echo "FAIL: phone runner must require both TTID and TTFD output for every flavor." >&2
  exit 1
}
grep -Fq 'did not emit $metric' "$DESKTOP_RUNNER" || {
  echo "FAIL: phone runner must fail clearly when a startup metric is missing." >&2
  exit 1
}

grep -Fq 'require_startup_metrics() {' "$DESKTOP_RUNNER" || {
  echo "FAIL: phone runner must centralize the TTID/TTFD validation for each flavor." >&2
  exit 1
}
grep -Fq 'require_macrobenchmark_cases() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must require both Macrobenchmark JUnit cases to execute." >&2
  exit 1
}
grep -Fq 'INSTRUMENTATION_STATUS: test=$test_name' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must inspect AndroidJUnitRunner's per-test status records." >&2
  exit 1
}
grep -Fq 'require_macrobenchmark_result_data() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must validate the exported Macrobenchmark JSON before reporting success." >&2
  exit 1
}
grep -Fq 'require_non_empty_startup_profile() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must reject empty or whitespace-only generated Startup Profile rules." >&2
  exit 1
}
grep -Fq 'require_non_empty_startup_profile' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must apply the Startup Profile content check after profile collection." >&2
  exit 1
}
phone_profile_copy="$(grep -n -F '      copy_recent_outputs profile' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
phone_profile_validate="$(grep -n -F '      require_non_empty_startup_profile' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
[ -n "$phone_profile_copy" ] && [ -n "$phone_profile_validate" ] && [ "$phone_profile_copy" -lt "$phone_profile_validate" ] || {
  echo "FAIL: phone runner must validate copied Startup Profile rules before declaring collection complete." >&2
  exit 1
}
grep -Fq 'validate_macrobenchmark_statistics() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must validate the actual per-iteration metric statistics." >&2
  exit 1
}
grep -Fq 'expected_iterations = 10' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must match the ten requested iterations." >&2
  exit 1
}
grep -Fq 'sample_count != expected_iterations' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must reject missing startup samples." >&2
  exit 1
}
grep -Fq 'minimum + 0 > median + 0 || median + 0 > maximum + 0' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must reject inconsistent minimum/median/maximum values." >&2
  exit 1
}
grep -Fq 'median_position = 0.5 * (sample_count - 1)' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must recompute AndroidX's interpolated 50th percentile from raw runs." >&2
  exit 1
}
grep -Fq 'reported median does not match the 50th percentile of its per-iteration runs' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must reject a reported median that disagrees with the raw runs." >&2
  exit 1
}
grep -Fq 'validate_macrobenchmark_statistics "$result_file"' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must apply metric-statistics validation to the copied result file." >&2
  exit 1
}
grep -Fq 'capture_device_environment() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must record the device conditions surrounding Macrobenchmark." >&2
  exit 1
}
grep -Fq 'capture_device_environment before' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must capture device conditions before Macrobenchmark." >&2
  exit 1
}
grep -Fq 'capture_device_environment after' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must capture device conditions after Macrobenchmark." >&2
  exit 1
}
grep -Fq '2>&1 || {' "$PHONE_RUNNER" || {
  echo "FAIL: device-condition snapshot file-write failures must be handled best-effort." >&2
  exit 1
}
grep -Fq 'could not write environment snapshot' "$PHONE_RUNNER" || {
  echo "FAIL: failed environment snapshot writes must be reported without masking instrumentation results." >&2
  exit 1
}
grep -Fq 'environment-before.txt' "$PHONE_RUNNER" && grep -Fq 'environment-after.txt' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must identify both device-condition snapshot files." >&2
  exit 1
}
for result_name in startupNoCompilation startupWithBaselineProfile; do
  grep -Fq "$result_name" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner must require JSON results for $result_name." >&2
    exit 1
  }
done
for metric_name in timeToInitialDisplayMs timeToFullDisplayMs; do
  grep -Fq "$metric_name" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner must require JSON $metric_name values for both comparison arms." >&2
    exit 1
  }
done

line_of() {
  grep -n -F -- "$1" "$DESKTOP_RUNNER" | head -n 1 | cut -d: -f1
}
full_run="$(line_of 'run_gradle_step "03-benchmark-full-benchmarkRelease"')"
full_check="$(line_of 'require_startup_metrics "03-benchmark-full-benchmarkRelease"')"
playstore_run="$(line_of 'run_gradle_step "04-benchmark-playstore-benchmarkRelease"')"
playstore_check="$(line_of 'require_startup_metrics "04-benchmark-playstore-benchmarkRelease"')"
for line in "$full_run" "$full_check" "$playstore_run" "$playstore_check"; do
  [ -n "$line" ] || {
    echo "FAIL: phone runner is missing a per-flavor startup result validation point." >&2
    exit 1
  }
done
[ "$full_run" -lt "$full_check" ] &&
[ "$full_check" -lt "$playstore_run" ] &&
[ "$playstore_run" -lt "$playstore_check" ] || {
  echo "FAIL: each flavor's TTID/TTFD output must be validated before the next flavor starts." >&2
  exit 1
}

# Every source regression must run exactly once in the pre-build workflow.
for test_script in \
  test-nuvio-phone-benchmark.sh \
  test-android-benchmark-bundle-assembly.sh \
  test-android-startup-readiness.sh \
  test-android-startup-metric-contract.sh; do
  count="$(awk -v expected="sh scripts/tests/$test_script" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      if (line == expected) count++
    }
    END { print count + 0 }
  ' "$WORKFLOW_SOURCE")"
  [ "$count" -eq 1 ] || {
    echo "FAIL: workflow must invoke sh scripts/tests/$test_script exactly once; found $count." >&2
    exit 1
  }
done

phone_case_check="$(grep -n -F '      require_macrobenchmark_cases' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
phone_copy="$(grep -n -F '      copy_recent_outputs benchmark' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
phone_json_check="$(grep -n -F '      require_macrobenchmark_result_data' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
[ -n "$phone_case_check" ] && [ -n "$phone_copy" ] && [ -n "$phone_json_check" ] &&
[ "$phone_case_check" -lt "$phone_copy" ] && [ "$phone_copy" -lt "$phone_json_check" ] || {
  echo "FAIL: phone runner must check both instrumentation cases, copy results, then validate JSON before reporting completion." >&2
  exit 1
}

printf 'PASS: both cold-start Macrobenchmark cases are declared and phone results require both test arms plus TTID/TTFD JSON data.\n'
