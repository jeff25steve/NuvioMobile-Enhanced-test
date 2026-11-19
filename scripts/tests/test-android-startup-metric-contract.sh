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
awk '
  /^[[:space:]]*@Test[[:space:]]*$/ { annotated=1; next }
  /^[[:space:]]*public void startupPartialWithoutBaselineProfile[[:space:]]*\(/ && annotated { found=1 }
  { if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*@Test[[:space:]]*$/) annotated=0 }
  END { exit !found }
' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare the partial no-profile control arm as a JUnit test." >&2
  exit 1
}
grep -Fq 'new CompilationMode.Partial(BaselineProfileMode.Disable, 3)' "$BENCHMARK_SOURCE" || {
  echo "FAIL: partial no-profile control must disable Baseline Profile compilation and use three warmup iterations." >&2
  exit 1
}
grep -Fq 'new CompilationMode.Partial(BaselineProfileMode.Require)' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Baseline Profile test must require the packaged profile during partial compilation." >&2
  exit 1
}
grep -Fq 'new StartupTimingMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: all three comparison arms must collect StartupTimingMetric." >&2
  exit 1
}
grep -Fq 'artJitCount' "$PHONE_RUNNER" && grep -Fq 'artVerifyClassSumMs' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must require ART JIT and runtime-verification metrics in all three startup arms." >&2
  exit 1
}
grep -Fq 'optional ART class-load metrics must be emitted as a complete pair' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must distinguish optional ART class-load tracepoints from zero measurements." >&2
  exit 1
}
grep -Fq 'Runtime class loading: not emitted' "$PHONE_RUNNER" || {
  echo "FAIL: phone report must identify unsupported ART class-load tracepoints instead of reporting a false zero." >&2
  exit 1
}
grep -Fq 'new ArtMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: all three comparison arms must collect ART runtime diagnostics." >&2
  exit 1
}
[ "$(grep -Fc 'new ArtMetric()' "$BENCHMARK_SOURCE")" -eq 3 ] || {
  echo "FAIL: ART diagnostics must be collected in all three cold-start comparison arms." >&2
  exit 1
}
grep -Fq 'StartupMode.COLD' "$BENCHMARK_SOURCE" || {
  echo "FAIL: all three comparison arms must measure cold starts." >&2
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
grep -Fq 'for metric in artJitCount artJitSumMs artVerifyClassCount artVerifyClassSumMs; do' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must require AndroidX ArtMetric output for every flavor." >&2
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
  echo "FAIL: phone runner must require all three Macrobenchmark JUnit cases to execute." >&2
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
grep -Fq 'metric_field(result_object, "warmupIterations")' "$PHONE_RUNNER" &&
  grep -Fq 'expected_warmup_iterations = 3' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must validate the exported warmup count for each startup comparison arm." >&2
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
grep -Fq 'read_thermal_status_from_snapshot() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must parse thermal status from the already-captured environment snapshot." >&2
  exit 1
}
grep -Fq '2|3|4|5|6)' "$PHONE_RUNNER" && grep -Fq 'THERMAL_STATUS_BEFORE' "$PHONE_RUNNER" || {
  echo "FAIL: moderate-or-higher pre-run thermal status must block measurement before instrumentation." >&2
  exit 1
}
grep -Fq 'write_thermal_integrity_summary "$THERMAL_STATUS_BEFORE" "$THERMAL_STATUS_AFTER"' "$PHONE_RUNNER" && \
  grep -Fq 'transient thermal-status changes between captures cannot be excluded' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must preserve and qualify pre/post thermal integrity evidence." >&2
  exit 1
}
grep -Fq 'append_macrobenchmark_execution_order() {' "$PHONE_RUNNER" && \
  grep -Fq 'Macrobenchmark JUnit execution order (from AndroidJUnitRunner status records)' "$PHONE_RUNNER" || {
  echo "FAIL: phone summary must report the actual AndroidJUnitRunner comparison-arm execution order." >&2
  exit 1
}

# Installed APK provenance must survive separate install/collect/measure shell invocations.
for expected in \
  'current-install-provenance.txt' \
  'load_install_provenance() {' \
  'App APK source SHA-256:' \
  'Test APK source SHA-256:' \
  'verify_installed_package_apk() {' \
  'pm path "$package_name"' \
  'Installed $package_label APK SHA-256 does not match the saved source APK' \
  'App installed APK verification:' \
  'Test installed APK verification:'; do
  grep -Fq "$expected" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner is missing APK provenance integrity contract '$expected'." >&2
    exit 1
  }
done
provenance_write_line="$(grep -n -F '  write_run_provenance' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
instrumentation_line="$(grep -n -F '  am instrument -w -r' "$PHONE_RUNNER" | head -n 1 | cut -d: -f1)"
[ -n "$provenance_write_line" ] && [ -n "$instrumentation_line" ] && [ "$provenance_write_line" -lt "$instrumentation_line" ] || {
  echo "FAIL: installed APK identity checks must complete before Macrobenchmark instrumentation starts." >&2
  exit 1
}
for result_name in startupNoCompilation startupPartialWithoutBaselineProfile startupWithBaselineProfile; do
  grep -Fq "$result_name" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner must require JSON results for $result_name." >&2
    exit 1
  }
done
for metric_name in timeToInitialDisplayMs timeToFullDisplayMs; do
  grep -Fq "$metric_name" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner must require JSON $metric_name values for all three comparison arms." >&2
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
  echo "FAIL: phone runner must check all three instrumentation cases, copy results, then validate JSON before reporting completion." >&2
  exit 1
}

grep -Fq 'Expected exactly one self-instrumenting instrumentation component' "$PHONE_RUNNER" &&
  grep -Fq 'androidx.test.runner.AndroidJUnitRunner' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must resolve exactly one expected self-instrumenting AndroidJUnitRunner." >&2
  exit 1
}
grep -Fq 'additional profiler artifacts are not accepted in a clean timing comparison' "$PHONE_RUNNER" &&
  grep -Fq 'Trace Iteration ' "$PHONE_RUNNER" || {
  echo "FAIL: phone result validation must reject extra profiler artifacts and validate per-iteration trace objects." >&2
  exit 1
}

for expected in \
  'reported_api + 0 < 33' \
  'ttfd_metric_count != 3' \
  'one for each startup comparison arm'; do
  grep -Fq "$expected" "$PHONE_RUNNER" || {
    echo "FAIL: phone startup result validation is missing required TTFD completeness guard '$expected'." >&2
    exit 1
  }
done

for expected in \
  'function json_member_object(source, field' \
  'reported_context = json_member_object(json, "context")' \
  'reported_build = json_member_object(reported_context, "build")' \
  'reported_version = json_member_object(reported_build, "version")' \
  'reported_api = metric_field(reported_version, "sdk")' \
  'context_object = json_member_object(json, "context")' \
  'build_object = json_member_object(context_object, "build")' \
  'version_object = json_member_object(build_object, "version")' \
  'device_model = metric_string_field(build_object, "model")' \
  'android_api = metric_field(version_object, "sdk")' \
  'cpu_core_count = metric_field(context_object, "cpuCoreCount")' \
  'cpu_max_frequency = metric_field(context_object, "cpuMaxFreqHz")' \
  'cpu_locked = metric_boolean_field(context_object, "cpuLocked")' \
  'memory_total = metric_field(context_object, "memTotalBytes")' \
  'art_mainline_version = metric_field(context_object, "artMainlineVersion")' \
  'sustained_performance = metric_boolean_field(context_object, "sustainedPerformanceModeEnabled")' \
  'os_codename = metric_string_field(context_object, "osCodenameAbbreviated")' \
  'reported_api + 0 < 33' \
  'ttfd_metric_count != 3'; do
  grep -Fq "$expected" "$PHONE_RUNNER" || {
    echo "FAIL: phone runner must bind SDK eligibility and summary metadata to AndroidX context '$expected'." >&2
    exit 1
  }
done

for expected in \
  'function json_member_value(source, field' \
  'value = json_member_value(source, field)' \
  'value = json_member_value(object, field)' \
  'runs_value = json_member_value(object, "runs")' \
  'if (found_count != 1) return ""' \
  'metric_object = substr(metric_text, metric_object_start)' \
  'result_object = "{" substr(result_rest, 1, RSTART - 1)' \
  'result_object = "{" result_rest'; do
  grep -Fq "$expected" "$PHONE_RUNNER" || {
    echo "FAIL: phone benchmark JSON reads must use exact direct object members and reject ambiguous duplicate keys '$expected'." >&2
    exit 1
  }
done
if grep -Fq 'start = index(object, needle)' "$PHONE_RUNNER" ||
   grep -Fq 'count_matches(object, "\\"runs\\"' "$PHONE_RUNNER"; then
  echo "FAIL: phone benchmark must not use unscoped substring lookups for JSON object fields or raw runs arrays." >&2
  exit 1
fi

if grep -Fq 'android_api = metric_field(json, "sdk")' "$PHONE_RUNNER" ||
   grep -Fq 'device_model = metric_string_field(json, "model")' "$PHONE_RUNNER" ||
   grep -Fq 'cpu_core_count = metric_field(json, "cpuCoreCount")' "$PHONE_RUNNER" ||
   grep -Fq 'cpu_max_frequency = metric_field(json, "cpuMaxFreqHz")' "$PHONE_RUNNER" ||
   grep -Fq 'cpu_locked = metric_boolean_field(json, "cpuLocked")' "$PHONE_RUNNER" ||
   grep -Fq 'memory_total = metric_field(json, "memTotalBytes")' "$PHONE_RUNNER" ||
   grep -Fq 'art_mainline_version = metric_field(json, "artMainlineVersion")' "$PHONE_RUNNER" ||
   grep -Fq 'sustained_performance = metric_boolean_field(json, "sustainedPerformanceModeEnabled")' "$PHONE_RUNNER" ||
   grep -Fq 'os_codename = metric_string_field(json, "osCodenameAbbreviated")' "$PHONE_RUNNER"; then
  echo "FAIL: phone summary must not read device metadata from unscoped JSON keys." >&2
  exit 1
fi

for expected in \
  'read_battery_saver_from_snapshot() {' \
  'write_battery_saver_integrity_summary() {' \
  'Battery saver (settings global low_power):' \
  'Battery Saver is enabled before Macrobenchmark' \
  'VERDICT: BATTERY-SAVER CONTAMINATED' \
  'Expected exactly one Macrobenchmark benchmarkData.json file for this instrumentation run' \
  '[ "$json_files" -eq 1 ]'; do
  grep -Fq "$expected" "$PHONE_RUNNER" || {
    echo "FAIL: phone benchmark is missing device-power or single-result-file integrity contract '$expected'." >&2
    exit 1
  }
done

printf 'PASS: all three cold-start Macrobenchmark cases and phone-result integrity contracts are present.\n'
