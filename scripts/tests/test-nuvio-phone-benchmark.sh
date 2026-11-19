#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNNER_SOURCE="$SCRIPT_DIR/nuvio-phone-benchmark.sh"
DESKTOP_RUNNER="$SCRIPT_DIR/run-android-startup-profile-benchmark.sh"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
READINESS_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupReadiness.java"

[ -f "$DESKTOP_RUNNER" ] || { echo "FAIL: desktop runner is missing." >&2; exit 1; }
[ -f "$GENERATOR_SOURCE" ] && [ -f "$BENCHMARK_SOURCE" ] && [ -f "$READINESS_SOURCE" ] || { echo "FAIL: baseline profile or readiness sources are missing." >&2; exit 1; }
sh "$SCRIPT_DIR/tests/test-android-startup-readiness.sh"
collect_block="$(sed -n '/baselineProfileRule.collect(/,/^[[:space:]]*);/p' "$GENERATOR_SOURCE")"
printf '%s\n' "$collect_block" | grep -Fq '"startup",' || {
  echo "FAIL: profile collection output prefix must remain startup." >&2
  exit 1
}
startup_profile_flags="$(printf '%s\n' "$collect_block" | grep -c '^                true,$' || true)"
[ "$startup_profile_flags" -eq 2 ] || {
  echo "FAIL: profile collection must keep includeInStartupProfile and strictStability enabled." >&2
  exit 1
}
for source in "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
  grep -Fq 'StartupReadiness.TARGET_APP_PACKAGE' "$source" || {
    echo "FAIL: benchmark journeys must target the isolated app through the shared readiness package constant." >&2
    exit 1
  }
  grep -Fq 'StartupReadiness.awaitHomeAndFullyDrawn(device)' "$source" || {
    echo "FAIL: both benchmark journeys must wait for shared Home and fully-drawn readiness." >&2
    exit 1
  }

  if [ "$source" = "$BENCHMARK_SOURCE" ]; then
    grep -Fq 'androidx-benchmark = "1.5.0"' "$ROOT_DIR/gradle/libs.versions.toml" || {
      echo "FAIL: Startup Insights usage must be checked against the repository's pinned AndroidX Benchmark version." >&2
      exit 1
    }
    grep -Fq 'import androidx.benchmark.ExperimentalConfig;' "$source" || {
      echo "FAIL: StartupBenchmark must import AndroidX ExperimentalConfig for Startup Insights." >&2
      exit 1
    }
    grep -Fq 'import androidx.benchmark.StartupInsightsConfig;' "$source" || {
      echo "FAIL: StartupBenchmark must import AndroidX StartupInsightsConfig." >&2
      exit 1
    }
    grep -Fq 'new ExperimentalConfig(null, new StartupInsightsConfig(true))' "$source" || {
      echo "FAIL: both startup comparison arms must enable trace-based Startup Insights without changing the Perfetto config." >&2
      exit 1
    }
    grep -Fq '                STARTUP_INSIGHTS_CONFIG,' "$source" || {
      echo "FAIL: MacrobenchmarkRule must use the ExperimentalConfig overload in both startup arms." >&2
      exit 1
    }
  fi
  if grep -Fq 'getTargetContext().getPackageName()' "$source"; then
    echo "FAIL: self-instrumenting instrumentation context must not be mistaken for the target app package." >&2
    exit 1
  fi
  if grep -Fq 'dumpWindowHierarchy' "$source"; then
    echo "FAIL: diagnostics must not XML-serialize potentially malformed accessibility text." >&2
    exit 1
  fi
  if grep -Fq 'ByteArrayOutputStream' "$source"; then
    echo "FAIL: obsolete accessibility hierarchy buffering must be removed." >&2
    exit 1
  fi
done

# Readiness polling and failure diagnostics live in one shared helper, not
# duplicated generator/benchmark code. Restrict the no-swallowed-error contract
# to marker lookup; best-effort diagnostics may legitimately report unavailable.
for expected in \
  'device.getCurrentPackageName()' \
  'Last package to report an accessibility event' \
  'markerVisible(UiDevice device, String marker)' \
  'HomeScreen marker visible now:' \
  'Fully-drawn marker visible now:'; do
  grep -Fq "$expected" "$READINESS_SOURCE" || {
    echo "FAIL: shared startup readiness helper is missing '$expected'." >&2
    exit 1
  }
done
if grep -Fq 'dumpWindowHierarchy' "$READINESS_SOURCE"; then
  echo "FAIL: readiness diagnostics must not serialize the complete accessibility hierarchy." >&2
  exit 1
fi

grep -Fq 'if (( API_LEVEL <= 33 )); then' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must protect authenticated Home measurement on API 33 as well as older rooted devices." >&2
  exit 1
}
grep -Fq 'shell id' "$DESKTOP_RUNNER" && grep -Fq 'shell su root id' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must mirror AndroidX's already-rooted and su root id capability checks." >&2
  exit 1
}
if grep -Fq "tr -d '\\\\r'" "$DESKTOP_RUNNER"; then
  echo "FAIL: desktop runner's adb output cleanup strips literal characters instead of carriage returns." >&2
  exit 1
fi
grep -Fq "tr -d '\\r'" "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must explicitly strip carriage returns from adb output." >&2
  exit 1
}

# Profile generation must target the isolated nonMinifiedRelease app variant.
grep -Fq ':baselineProfile:generateFullNonMinifiedReleaseBaselineProfile' "$DESKTOP_RUNNER" || {
  echo "FAIL: full profile capture must use the nonMinifiedRelease target variant." >&2
  exit 1
}
grep -Fq ':baselineProfile:generatePlaystoreNonMinifiedReleaseBaselineProfile' "$DESKTOP_RUNNER" || {
  echo "FAIL: playstore profile capture must use the nonMinifiedRelease target variant." >&2
  exit 1
}
grep -Fq 'profile_dir="androidApp/src/${flavor}NonMinifiedRelease/generated/baselineProfiles"' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must inspect the nonMinifiedRelease profile source set." >&2
  exit 1
}
if grep -Fq 'generateFullReleaseBaselineProfile' "$DESKTOP_RUNNER"; then
  echo "FAIL: desktop runner must not capture profiles using the production release variant." >&2
  exit 1
fi
grep -Fq 'baseline_profile_file=' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must resolve the generated baseline profile file explicitly." >&2
  exit 1
}
grep -Fq '[[ -s "$baseline_profile_file" ]]' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must reject an empty generated baseline profile." >&2
  exit 1
}
grep -Fq "grep -Eq '[^[:space:]]' \"\$baseline_profile_file\"" "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must reject a whitespace-only generated baseline profile." >&2
  exit 1
}
grep -Fq 'startup_profile_file=' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must resolve the generated startup profile explicitly." >&2
  exit 1
}
grep -Fq '[[ -s "$startup_profile_file" ]]' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must reject a missing or empty generated startup profile." >&2
  exit 1
}
grep -Fq "grep -Eq '[^[:space:]]' \"\$startup_profile_file\"" "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must reject a whitespace-only generated startup profile." >&2
  exit 1
}
grep -Fq 'require_non_empty_startup_profile() {' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must define Startup Profile validation." >&2
  exit 1
}
grep -Fq '      require_non_empty_startup_profile' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must execute Startup Profile validation after collection." >&2
  exit 1
}
grep -Fq 'require_non_empty_home_browse_profile() {' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must validate the separate Home browse Baseline Profile output." >&2
  exit 1
}
grep -Fq '      require_non_empty_home_browse_profile' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must validate the Home browse profile after collection." >&2
  exit 1
}
grep -Fq '*home_browse-baseline-prof*.txt' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must validate the named Home browse Baseline Profile output." >&2
  exit 1
}
grep -Fq 'horizontally scrollable catalogue shelf' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must tell users that browse profile collection requires a populated, horizontally scrollable Home catalogue." >&2
  exit 1
}
grep -Fq 'horizontally scrollable catalogue shelf' "$DESKTOP_RUNNER" || {
  echo "FAIL: desktop runner must tell users that browse profile collection requires a populated, horizontally scrollable Home catalogue." >&2
  exit 1
}
grep -Fq 'TEST_MEDIA_ROOT="${MEDIA_ROOT%/}/$TEST_PACKAGE"' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must scope AndroidX output collection to the self-instrumenting test package." >&2
  exit 1
}
grep -Fq 'find "$TEST_MEDIA_ROOT" -type f -newer "$MARKER"' "$RUNNER_SOURCE" || {
  echo "FAIL: profile and Macrobenchmark artifacts must be discovered only beneath the test package media directory." >&2
  exit 1
}
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BUNDLE="$TMP/bundle"
mkdir -p "$BUNDLE" "$TMP/media" "$TMP/bin"
cp "$RUNNER_SOURCE" "$BUNDLE/nuvio-phone-benchmark.sh"
for flavor in full playstore; do
  : > "$BUNDLE/Nuvio-$flavor-BenchmarkRelease.apk"
  : > "$BUNDLE/Nuvio-$flavor-BenchmarkRelease-Tests.apk"
  : > "$BUNDLE/Nuvio-$flavor-NonMinifiedRelease.apk"
  : > "$BUNDLE/Nuvio-$flavor-NonMinifiedRelease-Tests.apk"
done

cat > "$TMP/bin/getprop" <<'STUB'
#!/bin/sh
case "$1" in
  ro.build.version.sdk) echo "${FAKE_API:-34}" ;;
  ro.build.fingerprint) echo "${FAKE_FINGERPRINT:-fixture/fingerprint}" ;;
  sys.boot_completed) echo "${FAKE_BOOT_COMPLETED:-1}" ;;
  ro.product.cpu.abi) echo "${FAKE_ABI:-arm64-v8a}" ;;
  ro.kernel.qemu) echo 0 ;;
  ro.hardware) echo qcom ;;
  *) echo test-device ;;
esac
STUB
cat > "$TMP/bin/pm" <<'STUB'
#!/bin/sh
printf 'pm %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$1" in
  list) echo 'instrumentation:com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner (target=com.nuvio.android.baselineprofile)' ;;
  install)
    if [ "${FAKE_INCOMPATIBLE:-0}" = "1" ] && [ "$2" = "-r" ] && [ ! -f "$FAKE_INCOMPATIBLE_ONCE" ]; then
      : > "$FAKE_INCOMPATIBLE_ONCE"
      echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'
      exit 1
    fi
    echo 'Success'
    ;;
  *) exit 0 ;;
esac
STUB
cat > "$TMP/bin/monkey" <<'STUB'
#!/bin/sh
printf 'monkey %s\n' "$*" >> "$FAKE_COMMAND_LOG"
STUB
cat > "$TMP/bin/am" <<'STUB'
#!/bin/sh
printf 'am %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case " $* " in
  *macrobenchmark*)
    mkdir -p "$FAKE_MEDIA_DIR"
    rm -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
    trace_file_limit=30
    empty_trace_index=-1
    case "${FAKE_BENCHMARK_JSON:-complete}" in
      missing-trace-file) trace_file_limit=29 ;;
      empty-trace-file) empty_trace_index=29 ;;
    esac
    trace_index=0
    for trace_prefix in startupNoCompilation startupPartialWithoutBaselineProfile startupWithBaselineProfile; do
      i=0
      while [ "$i" -lt 10 ]; do
        trace_file="$trace_prefix-trace-$i.perfetto-trace"
        if [ "$trace_index" -lt "$trace_file_limit" ]; then
          if [ "$trace_index" -eq "$empty_trace_index" ]; then
            : > "$FAKE_MEDIA_DIR/$trace_file"
          else
            printf 'fixture perfetto trace\n' > "$FAKE_MEDIA_DIR/$trace_file"
          fi
        fi
        trace_index=$((trace_index + 1))
        i=$((i + 1))
      done
    done
    case "${FAKE_TEST_ORDER:-default}" in
      default)
        test_order="startupNoCompilation startupPartialWithoutBaselineProfile startupWithBaselineProfile"
        ;;
      reverse)
        test_order="startupWithBaselineProfile startupPartialWithoutBaselineProfile startupNoCompilation"
        ;;
      *)
        echo "Unknown fake test execution order: $FAKE_TEST_ORDER" >&2
        exit 2
        ;;
    esac
    for test_name in $test_order; do
      if [ "${FAKE_MISSING_TEST_CASE:-}" != "$test_name" ]; then
        printf 'INSTRUMENTATION_STATUS: class=com.nuvio.android.baselineprofile.StartupBenchmark\n'
        printf 'INSTRUMENTATION_STATUS: test=%s\n' "$test_name"
        printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
      fi
    done
    cat > "$FAKE_MEDIA_DIR/fake-benchmarkData.json" <<'JSON'
{
  "context": {"build": {"model": "Fixture phone", "version": {"sdk": 35}}, "cpuCoreCount": 8, "cpuLocked": true, "cpuMaxFreqHz": 2800000000, "memTotalBytes": 12884901888, "sustainedPerformanceModeEnabled": false, "artMainlineVersion": 123456789, "osCodenameAbbreviated": "REL", "compilationMode": "None"},
  "benchmarks": [
    {
      "className": "com.nuvio.android.baselineprofile.StartupBenchmark",
      "name": "startupNoCompilation",
      "metrics": {
        "timeToInitialDisplayMs": {"minimum": 400, "maximum": 490, "median": 445, "coefficientOfVariation": 0.068037086608932, "runs": [400,410,420,430,440,450,460,470,480,490]},
        "timeToFullDisplayMs": {"minimum": 700, "maximum": 790, "median": 745, "coefficientOfVariation": 0.040639602068423, "runs": [700,710,720,730,740,750,760,770,780,790]},
        "artJitCount": {"minimum": 1, "maximum": 10, "median": 5.5, "coefficientOfVariation": 0.5504818825631803, "runs": [1,2,3,4,5,6,7,8,9,10]},
        "artJitSumMs": {"minimum": 10, "maximum": 19, "median": 14.5, "coefficientOfVariation": 0.20880347269637875, "runs": [10,11,12,13,14,15,16,17,18,19]},
        "artVerifyClassCount": {"minimum": 0, "maximum": 9, "median": 4.5, "coefficientOfVariation": 0.6728111897994427, "runs": [0,1,2,3,4,5,6,7,8,9]},
        "artVerifyClassSumMs": {"minimum": 0, "maximum": 9, "median": 4.5, "coefficientOfVariation": 0.6728111897994427, "runs": [0,1,2,3,4,5,6,7,8,9]},
        "artClassLoadCount": {"minimum": 0, "maximum": 4, "median": 2, "coefficientOfVariation": 0.7453559924999299, "runs": [0,0,1,1,2,2,3,3,4,4]},
        "artClassLoadSumMs": {"minimum": 0, "maximum": 4, "median": 2, "coefficientOfVariation": 0.7453559924999299, "runs": [0,0,1,1,2,2,3,3,4,4]}
      },
      "sampledMetrics": {},
      "warmupIterations": 0,
      "repeatIterations": 10,
      "thermalThrottleSleepSeconds": 0,
      "profilerOutputs": [
        {"type":"PerfettoTrace","label":"Trace Iteration 0","filename":"startupNoCompilation-trace-0.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 1","filename":"startupNoCompilation-trace-1.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 2","filename":"startupNoCompilation-trace-2.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 3","filename":"startupNoCompilation-trace-3.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 4","filename":"startupNoCompilation-trace-4.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 5","filename":"startupNoCompilation-trace-5.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 6","filename":"startupNoCompilation-trace-6.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 7","filename":"startupNoCompilation-trace-7.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 8","filename":"startupNoCompilation-trace-8.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 9","filename":"startupNoCompilation-trace-9.perfetto-trace"}
      ]
    },
    {
      "className": "com.nuvio.android.baselineprofile.StartupBenchmark",
      "name": "startupPartialWithoutBaselineProfile",
      "metrics": {
        "timeToInitialDisplayMs": {"minimum": 360, "maximum": 450, "median": 405, "coefficientOfVariation": 0.074756798866605, "runs": [360,370,380,390,400,410,420,430,440,450]},
        "timeToFullDisplayMs": {"minimum": 650, "maximum": 740, "median": 695, "coefficientOfVariation": 0.043563314447446, "runs": [650,660,670,680,690,700,710,720,730,740]},
        "artJitCount": {"minimum": 1, "maximum": 10, "median": 5.5, "coefficientOfVariation": 0.5504818825631803, "runs": [1,2,3,4,5,6,7,8,9,10]},
        "artJitSumMs": {"minimum": 10, "maximum": 19, "median": 14.5, "coefficientOfVariation": 0.20880347269637875, "runs": [10,11,12,13,14,15,16,17,18,19]},
        "artVerifyClassCount": {"minimum": 0, "maximum": 9, "median": 4.5, "coefficientOfVariation": 0.6728111897994427, "runs": [0,1,2,3,4,5,6,7,8,9]},
        "artVerifyClassSumMs": {"minimum": 0, "maximum": 9, "median": 4.5, "coefficientOfVariation": 0.6728111897994427, "runs": [0,1,2,3,4,5,6,7,8,9]},
        "artClassLoadCount": {"minimum": 0, "maximum": 4, "median": 2, "coefficientOfVariation": 0.7453559924999299, "runs": [0,0,1,1,2,2,3,3,4,4]},
        "artClassLoadSumMs": {"minimum": 0, "maximum": 4, "median": 2, "coefficientOfVariation": 0.7453559924999299, "runs": [0,0,1,1,2,2,3,3,4,4]}
      },
      "sampledMetrics": {},
      "warmupIterations": 3,
      "repeatIterations": 10,
      "thermalThrottleSleepSeconds": 0,
      "profilerOutputs": [
        {"type":"PerfettoTrace","label":"Trace Iteration 0","filename":"startupPartialWithoutBaselineProfile-trace-0.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 1","filename":"startupPartialWithoutBaselineProfile-trace-1.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 2","filename":"startupPartialWithoutBaselineProfile-trace-2.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 3","filename":"startupPartialWithoutBaselineProfile-trace-3.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 4","filename":"startupPartialWithoutBaselineProfile-trace-4.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 5","filename":"startupPartialWithoutBaselineProfile-trace-5.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 6","filename":"startupPartialWithoutBaselineProfile-trace-6.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 7","filename":"startupPartialWithoutBaselineProfile-trace-7.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 8","filename":"startupPartialWithoutBaselineProfile-trace-8.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 9","filename":"startupPartialWithoutBaselineProfile-trace-9.perfetto-trace"}
      ]
    },
    {
      "className": "com.nuvio.android.baselineprofile.StartupBenchmark",
      "name": "startupWithBaselineProfile",
      "metrics": {
        "timeToInitialDisplayMs": {"minimum": 330, "maximum": 420, "median": 375, "coefficientOfVariation": 0.080737342775933, "runs": [330,340,350,360,370,380,390,400,410,420]},
        "timeToFullDisplayMs": {"minimum": 610, "maximum": 700, "median": 655, "coefficientOfVariation": 0.046223669528206, "runs": [610,620,630,640,650,660,670,680,690,700]},
        "artJitCount": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]},
        "artJitSumMs": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]},
        "artVerifyClassCount": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]},
        "artVerifyClassSumMs": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]},
        "artClassLoadCount": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]},
        "artClassLoadSumMs": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0,0,0,0,0,0,0,0,0,0]}
      },
      "sampledMetrics": {},
      "warmupIterations": 0,
      "repeatIterations": 10,
      "thermalThrottleSleepSeconds": 0,
      "profilerOutputs": [
        {"type":"PerfettoTrace","label":"Trace Iteration 0","filename":"startupWithBaselineProfile-trace-0.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 1","filename":"startupWithBaselineProfile-trace-1.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 2","filename":"startupWithBaselineProfile-trace-2.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 3","filename":"startupWithBaselineProfile-trace-3.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 4","filename":"startupWithBaselineProfile-trace-4.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 5","filename":"startupWithBaselineProfile-trace-5.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 6","filename":"startupWithBaselineProfile-trace-6.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 7","filename":"startupWithBaselineProfile-trace-7.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 8","filename":"startupWithBaselineProfile-trace-8.perfetto-trace"},
        {"type":"PerfettoTrace","label":"Trace Iteration 9","filename":"startupWithBaselineProfile-trace-9.perfetto-trace"}
      ]
    }
  ]
}
JSON
    if [ "${FAKE_BENCHMARK_JSON:-complete}" = "no-classload" ]; then
      awk '
        /"artClassLoad(Count|SumMs)"/ { next }
        /"artVerifyClassSumMs":/ { sub(/,[[:space:]]*$/, "") }
        { print }
      ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
      mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
    fi
    if [ "${FAKE_API:-35}" = "33" ]; then
      sed 's/"sdk": 35/"sdk": 33/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
      mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
    fi
    if [ "${FAKE_BENCHMARK_JSON:-complete}" = "missing-trace-metadata" ]; then
      awk '
        /startupWithBaselineProfile-trace-9.perfetto-trace/ { next }
        /startupWithBaselineProfile-trace-8.perfetto-trace/ { sub(/},[[:space:]]*$/, "}"); }
        { print }
      ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
      mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
    fi
    case "${FAKE_BENCHMARK_JSON:-complete}" in
      complete|missing-trace-metadata|missing-trace-file|empty-trace-file|no-classload) ;;
      empty) printf '{"benchmarks":[]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json" ;;
      missing-profile)
        printf '{"benchmarks":[{"name":"startupNoCompilation","metrics":{"timeToInitialDisplayMs":{"median":1},"timeToFullDisplayMs":{"median":2}}}]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-ttfd)
        printf '{"benchmarks":[{"name":"startupNoCompilation","metrics":{"timeToInitialDisplayMs":{"median":1}}},{"name":"startupWithBaselineProfile","metrics":{"timeToInitialDisplayMs":{"median":1}}}]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      one-arm-metrics)
        printf '{"benchmarks":[{"name":"startupNoCompilation","metrics":{"timeToInitialDisplayMs":{"median":1},"timeToFullDisplayMs":{"median":2}}},{"name":"startupWithBaselineProfile","metrics":{}}]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-repeat-iterations)
        sed 's/"repeatIterations": 10/"repeatIterations": 9/g' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-warmup-iterations)
        sed 's/"warmupIterations": 3/"warmupIterations": 0/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      short-samples)
        sed 's/480,490/480/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      invalid-stats)
        sed 's/"minimum": 400, "maximum": 490, "median": 445/"minimum": 450, "maximum": 490, "median": 445/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-median)
        # Keep the reported median within min/max, but make it disagree with
        # the 50th percentile recomputed from the ten raw samples.
        sed 's/"median": 445/"median": 446/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-art-cv)
        sed 's/"coefficientOfVariation": 0.5504818825631803/"coefficientOfVariation": 0.5604818825631803/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      negative-art-run)
        sed 's/"runs": \[1,2,3,4,5,6,7,8,9,10\]/"runs": [-1,2,3,4,5,6,7,8,9,10]/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      unpaired-classload)
        awk '
          /"artClassLoadSumMs":/ { next }
          /"artClassLoadCount":/ { sub(/,[[:space:]]*$/, "") }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-art-jit)
        awk '/"artJitCount":/ { next } { print }' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-cv)
        # Keep CV numeric and plausible, but inconsistent with the raw runs.
        sed 's/"coefficientOfVariation": 0.068037086608932/"coefficientOfVariation": 0.070037086608932/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-cv)
        sed 's/, "coefficientOfVariation": 0.068037086608932//' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-samples)
        sed 's/, "runs": \[400,410,420,430,440,450,460,470,480,490\]//' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      *) echo "Unknown fake benchmark JSON fixture: $FAKE_BENCHMARK_JSON" >&2; exit 2 ;;
    esac
    if [ "${FAKE_FOREIGN_OUTPUTS:-0}" = "1" ]; then
      mkdir -p "$FAKE_MEDIA_ROOT/com.example.other"
      printf '{"benchmarks":[]}' > "$FAKE_MEDIA_ROOT/com.example.other/foreign-benchmarkData.json"
    fi
    : > "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
    echo 'OK (3 tests)'
    if [ "${FAKE_NEGATIVE_STATUS_CODE:-0}" = "1" ]; then
      printf 'INSTRUMENTATION_STATUS_CODE: -2\n'
    fi
    # Activity.RESULT_OK is -1; AndroidJUnitRunner emits this on normal completion.
    printf 'INSTRUMENTATION_CODE: -1\n'
    ;;
  *BaselineProfile*)
    mkdir -p "$FAKE_MEDIA_DIR"
    if [ "${FAKE_MISSING_STARTUP_PROFILE:-0}" != "1" ]; then
      if [ "${FAKE_EMPTY_STARTUP_PROFILE:-0}" = "1" ]; then
        : > "$FAKE_MEDIA_DIR/startup-startup-prof.txt"
      elif [ "${FAKE_BLANK_STARTUP_PROFILE:-0}" = "1" ]; then
        printf '  \n\t\n' > "$FAKE_MEDIA_DIR/startup-startup-prof.txt"
      else
        printf 'Lcom/nuvio/Foo;->bar()V\n' > "$FAKE_MEDIA_DIR/startup-startup-prof.txt"
      fi
    fi
    if [ "${FAKE_MISSING_HOME_BROWSE_PROFILE:-0}" != "1" ]; then
      if [ "${FAKE_EMPTY_HOME_BROWSE_PROFILE:-0}" = "1" ]; then
        : > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
      elif [ "${FAKE_BLANK_HOME_BROWSE_PROFILE:-0}" = "1" ]; then
        printf '  \n\t\n' > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
      else
        printf 'Lcom/nuvio/HomeCatalog;->scroll()V\n' > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
      fi
    fi
    if [ "${FAKE_FOREIGN_OUTPUTS:-0}" = "1" ]; then
      mkdir -p "$FAKE_MEDIA_ROOT/com.example.other"
      : > "$FAKE_MEDIA_ROOT/com.example.other/foreign-baseline-prof-decoy.txt"
    fi
    echo 'OK (1 test)'
    # The final instrumentation result code is Activity.RESULT_OK, not a per-test status.
    printf 'INSTRUMENTATION_CODE: -1\\n'
    ;;
esac
STUB
cat > "$TMP/bin/dumpsys" <<'STUB'
#!/bin/sh
case "$1" in
  battery)
    if [ "${FAKE_ENV_DIAGNOSTICS_FAIL:-0}" = "1" ]; then
      echo 'level: unavailable'
      exit 1
    fi
    cat <<'BATTERY'
AC powered: true
USB powered: false
Wireless powered: false
status: 2
level: 88
voltage: 4200
temperature: 320
BATTERY
    ;;
  thermalservice)
    if [ "${FAKE_ENV_DIAGNOSTICS_FAIL:-0}" = "1" ]; then
      echo 'thermal service unavailable'
      exit 1
    fi
    thermal_status="${FAKE_THERMAL_STATUS:-0}"
    if [ -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed" ]; then
      thermal_status="${FAKE_THERMAL_STATUS_AFTER:-$thermal_status}"
    fi
    printf 'Current Thermal Status: %s (fixture)\nThermal Status: %s\n' "$thermal_status" "$thermal_status"
    ;;
  window)
    case "$2" in
      windows)
        if [ "${FAKE_WIFI_DEBUG_PROMPT:-0}" = "1" ]; then
          echo 'mCurrentFocus=Window{abc com.android.systemui/.wifi.WifiDebuggingActivity}'
        else
          echo 'mCurrentFocus=Window{abc com.android.launcher/.Launcher}'
        fi
        ;;
      *) exit 1 ;;
    esac
    ;;
  activity)
    case "$2" in
      activities)
        if [ "${FAKE_WIFI_DEBUG_PROMPT:-0}" = "1" ]; then
          echo 'topResumedActivity=ActivityRecord{abc com.android.systemui/.wifi.WifiDebuggingActivity}'
        else
          echo 'topResumedActivity=ActivityRecord{abc com.android.launcher/.Launcher}'
        fi
        ;;
      *) exit 1 ;;
    esac
    ;;
  *) exit 1 ;;
esac
STUB
cat > "$TMP/bin/settings" <<'STUB'
#!/bin/sh
if [ "${FAKE_ENV_DIAGNOSTICS_FAIL:-0}" = "1" ]; then
  echo 'unavailable'
  exit 1
fi
case "$2:$3" in
  global:low_power) echo 0 ;;
  system:screen_brightness_mode) echo 0 ;;
  system:screen_brightness) echo 128 ;;
  *) echo null ;;
esac
STUB
cat > "$TMP/bin/id" <<'STUB'
#!/bin/sh
if [ "${FAKE_SHELL_ROOT:-0}" = "1" ]; then
  printf 'uid=0(root) gid=0(root)\n'
else
  printf 'uid=2000(shell) gid=2000(shell)\n'
fi
STUB
cat > "$TMP/bin/su" <<'STUB'
#!/bin/sh
[ "${FAKE_SU_ROOT:-0}" = "1" ] || exit 1
printf 'uid=0(root) gid=0(root)\n'
STUB
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:$PATH"
export FAKE_COMMAND_LOG="$TMP/commands.log" FAKE_MEDIA_ROOT="$TMP/media"
export FAKE_MEDIA_DIR="$FAKE_MEDIA_ROOT/com.nuvio.android.baselineprofile"
export NUVIO_PHONE_OUTPUT_DIR="$TMP/output" NUVIO_PHONE_MEDIA_ROOT="$FAKE_MEDIA_ROOT"
export NUVIO_PHONE_STAGE_DIR="$TMP/staged"

# Unsupported devices must fail before any APK installation.
if FAKE_API=32 sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile full >/dev/null 2>&1; then
  echo 'FAIL: API 32 should be rejected.' >&2
  exit 1
fi
[ ! -s "$FAKE_COMMAND_LOG" ] || { echo 'FAIL: unsupported device invoked package manager.' >&2; exit 1; }
if FAKE_ABI=x86_64 sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile full >/dev/null 2>&1; then
  echo 'FAIL: non-arm64 device should be rejected.' >&2
  exit 1
fi
[ ! -s "$FAKE_COMMAND_LOG" ] || { echo 'FAIL: unsupported ABI invoked package manager.' >&2; exit 1; }

for flavor in full playstore; do
  sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile "$flavor" >/dev/null
  for startup_profile_fixture in missing empty blank; do
    case "$startup_profile_fixture" in
      missing) startup_profile_env=FAKE_MISSING_STARTUP_PROFILE ;;
      empty) startup_profile_env=FAKE_EMPTY_STARTUP_PROFILE ;;
      blank) startup_profile_env=FAKE_BLANK_STARTUP_PROFILE ;;
    esac
    if env "$startup_profile_env=1" sh "$BUNDLE/nuvio-phone-benchmark.sh" collect "$flavor" > "$TMP/$flavor-$startup_profile_fixture-startup-profile.log" 2>&1; then
      echo "FAIL: phone runner accepted a $startup_profile_fixture generated Startup Profile ($flavor)." >&2
      exit 1
    fi
    case "$startup_profile_fixture" in
      missing)
        expected_startup_failure="No startup-prof text profile was produced"
        ;;
      empty|blank)
        expected_startup_failure="Generated startup profile contains no rules"
        ;;
    esac
    grep -Fq "$expected_startup_failure" "$TMP/$flavor-$startup_profile_fixture-startup-profile.log" || {
      echo "FAIL: $startup_profile_fixture Startup Profile rejection did not identify the missing rules ($flavor)." >&2
      cat "$TMP/$flavor-$startup_profile_fixture-startup-profile.log" >&2
      exit 1
    }
  done
  for browse_profile_fixture in missing empty blank; do
    case "$browse_profile_fixture" in
      missing) browse_profile_env=FAKE_MISSING_HOME_BROWSE_PROFILE ;;
      empty) browse_profile_env=FAKE_EMPTY_HOME_BROWSE_PROFILE ;;
      blank) browse_profile_env=FAKE_BLANK_HOME_BROWSE_PROFILE ;;
    esac
    if env "$browse_profile_env=1" sh "$BUNDLE/nuvio-phone-benchmark.sh" collect "$flavor" > "$TMP/$flavor-$browse_profile_fixture-home-browse-profile.log" 2>&1; then
      echo "FAIL: phone runner accepted a $browse_profile_fixture Home browse Baseline Profile ($flavor)." >&2
      exit 1
    fi
    case "$browse_profile_fixture" in
      missing)
        expected_browse_failure="No home_browse Baseline Profile text file was produced"
        ;;
      empty|blank)
        expected_browse_failure="Generated Home browse Baseline Profile contains no rules"
        ;;
    esac
    grep -Fq "$expected_browse_failure" "$TMP/$flavor-$browse_profile_fixture-home-browse-profile.log" || {
      echo "FAIL: $browse_profile_fixture Home browse profile rejection did not identify the missing rules ($flavor)." >&2
      cat "$TMP/$flavor-$browse_profile_fixture-home-browse-profile.log" >&2
      exit 1
    }
  done
  if sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" >/dev/null 2>&1; then
    echo 'FAIL: measure must reject the profile-generation app state.' >&2
    exit 1
  fi
  FAKE_FOREIGN_OUTPUTS=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" run "$flavor" > "$TMP/$flavor-isolation.log" 2>&1
  grep -q 'androidx.benchmark.enabledRules BaselineProfile' "$FAKE_COMMAND_LOG" || {
    echo "FAIL: combined run did not invoke Baseline Profile collection ($flavor)." >&2
    cat "$FAKE_COMMAND_LOG" >&2
    exit 1
  }
  grep -q 'androidx.benchmark.enabledRules macrobenchmark' "$FAKE_COMMAND_LOG" || {
    echo "FAIL: combined run did not invoke Macrobenchmark measurement ($flavor)." >&2
    cat "$FAKE_COMMAND_LOG" >&2
    exit 1
  }
  benchmark_result_dir="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print -quit)"
  [ -n "$benchmark_result_dir" ] || {
    echo "FAIL: combined run must store Macrobenchmark artifacts in a measure-* directory ($flavor)." >&2
    exit 1
  }
  [ -s "$benchmark_result_dir/fake-benchmarkData.json" ] || {
    echo "FAIL: measure-* directory must contain the Macrobenchmark JSON result ($flavor)." >&2
    exit 1
  }
  [ -s "$benchmark_result_dir/benchmark-summary.txt" ] || {
    echo "FAIL: measure-* directory must contain the human-readable benchmark summary ($flavor)." >&2
    exit 1
  }
  grep -Fq '10 samples per comparison arm' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'descriptive only' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'timeToInitialDisplayMs' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'timeToFullDisplayMs' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'ART runtime compilation and verification' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'No compilation: JIT 5.5 methods / 14.50 ms; runtime verification 4.5 classes / 4.50 ms' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Partial without Baseline Profile: JIT 5.5 methods / 14.50 ms; runtime verification 4.5 classes / 4.50 ms' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Baseline Profile: JIT 0.0 methods / 0.00 ms; runtime verification 0.0 classes / 0.00 ms' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Runtime class loading: no compilation 2.0 classes / 2.00 ms; partial without profile 2.0 classes / 2.00 ms; Baseline Profile 0.0 classes / 0.00 ms' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Benchmark device context' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Device: Fixture phone | Android API: 35' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Install path: APK sideload via pm install; not Google Play delivery.' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'The playstore build-flavor label does not mean this APK was installed from Google Play.' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'API 34+ CompilationMode.None uses verify and clears app ART profiles before measurement.' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'CPU: 8 cores | max frequency 2800000000 Hz | clocks locked: true' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Memory: 12884901888 bytes | ART Mainline: 123456789 | sustained performance mode: false' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Per-iteration samples and matching Perfetto traces' "$benchmark_result_dir/benchmark-summary.txt"
  FAKE_TEST_ORDER=reverse sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-reverse-test-order.log" 2>&1
  reverse_result_dir="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)"
  reverse_summary="$reverse_result_dir/benchmark-summary.txt"
  grep -Fq 'Macrobenchmark JUnit execution order (from AndroidJUnitRunner status records)' "$reverse_summary"
  order_profile_line="$(grep -n -F '  1. startupWithBaselineProfile' "$reverse_summary" | tail -n 1 | cut -d: -f1)"
  order_partial_line="$(grep -n -F '  2. startupPartialWithoutBaselineProfile' "$reverse_summary" | tail -n 1 | cut -d: -f1)"
  order_no_compilation_line="$(grep -n -F '  3. startupNoCompilation' "$reverse_summary" | tail -n 1 | cut -d: -f1)"
  [ -n "$order_profile_line" ] && [ -n "$order_partial_line" ] && [ -n "$order_no_compilation_line" ] &&
    [ "$order_profile_line" -lt "$order_partial_line" ] && [ "$order_partial_line" -lt "$order_no_compilation_line" ] || {
      echo "FAIL: benchmark summary did not preserve the reversed AndroidJUnitRunner test execution order ($flavor)." >&2
      cat "$reverse_summary" >&2
      exit 1
    }
  for iteration in 0 1 2 3 4 5 6 7 8 9; do
    no_ttid=$((400 + iteration * 10))
    no_ttfd=$((700 + iteration * 10))
    partial_ttid=$((360 + iteration * 10))
    partial_ttfd=$((650 + iteration * 10))
    profile_ttid=$((330 + iteration * 10))
    profile_ttfd=$((610 + iteration * 10))
    grep -Fq "Iteration $iteration: TTID $no_ttid.0 ms | TTFD $no_ttfd.0 ms | trace startupNoCompilation-trace-$iteration.perfetto-trace" "$benchmark_result_dir/benchmark-summary.txt"
    grep -Fq "Iteration $iteration: TTID $partial_ttid.0 ms | TTFD $partial_ttfd.0 ms | trace startupPartialWithoutBaselineProfile-trace-$iteration.perfetto-trace" "$benchmark_result_dir/benchmark-summary.txt"
    grep -Fq "Iteration $iteration: TTID $profile_ttid.0 ms | TTFD $profile_ttfd.0 ms | trace startupWithBaselineProfile-trace-$iteration.perfetto-trace" "$benchmark_result_dir/benchmark-summary.txt"
  done
  grep -Fq -- '-40.0 ms (-9.0%)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq -- '-50.0 ms (-6.7%)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq -- '-30.0 ms (-7.4%)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq -- '-40.0 ms (-5.8%)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq -- '-70.0 ms (-15.7%)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq -- '-90.0 ms (-12.1%)' "$benchmark_result_dir/benchmark-summary.txt"
  if ! FAKE_BENCHMARK_JSON=no-classload sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-no-classload.log" 2>&1; then
    echo "FAIL: runner should accept AndroidX output where optional ART class-load tracepoints are unavailable ($flavor)." >&2
    cat "$TMP/$flavor-no-classload.log" >&2
    exit 1
  fi
  no_load_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  grep -Fq 'Runtime class loading: not emitted (ART class-load tracepoints unavailable on this device)' "$no_load_summary"
  if grep -R -Fq "$FAKE_MEDIA_ROOT/com.example.other/" "$TMP/output/$flavor"; then
    echo "FAIL: phone runner copied benchmark/profile output from another app media directory ($flavor)." >&2
    exit 1
  fi
  baseline_profile_copy="$(find "$TMP/output/$flavor" -name '*baseline-prof*.txt' -print -quit)"
  profile_result_dir="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'run-*' -print | sort | tail -n 1)"
  [ -n "$profile_result_dir" ] || {
    echo "FAIL: combined run must store a run-* directory ($flavor)." >&2
    exit 1
  }
  home_browse_profile_copy="$(find "$profile_result_dir" -name '*home_browse-baseline-prof*.txt' -print -quit)"
  startup_profile_copy="$(find "$profile_result_dir" -name '*startup-prof*.txt' -print -quit)"
  test -n "$home_browse_profile_copy"
  test -s "$home_browse_profile_copy"
  grep -Eq '[^[:space:]]' "$home_browse_profile_copy"
  test -n "$startup_profile_copy"
  test -s "$startup_profile_copy"
  grep -Eq '[^[:space:]]' "$startup_profile_copy"
  test -n "$(find "$TMP/output/$flavor" -name '*benchmarkData.json' -print -quit)"
  environment_before="$(find "$TMP/output/$flavor" -name 'environment-before.txt' -print -quit)"
  environment_after="$(find "$TMP/output/$flavor" -name 'environment-after.txt' -print -quit)"
  test -s "$environment_before"
  test -s "$environment_after"
  grep -Fq 'level: 88' "$environment_before"
  grep -Fq 'temperature: 320' "$environment_before"
  grep -Fq 'Current Thermal Status: 0' "$environment_before"
  thermal_integrity="$(dirname "$environment_before")/thermal-integrity.txt"
  test -s "$thermal_integrity"
  grep -Fq 'Thermal status before measurement: 0 (NONE)' "$thermal_integrity"
  grep -Fq 'Thermal status after measurement: 0 (NONE)' "$thermal_integrity"
  grep -Fq 'NO MODERATE-OR-HIGHER THERMAL STATUS OBSERVED AT THE TWO SNAPSHOTS' "$thermal_integrity"
  grep -Fq 'thermal-integrity.txt' "$(dirname "$environment_before")/copied-files.txt"
  grep -Fq 'Thermal integrity' "$(dirname "$environment_before")/benchmark-summary.txt"
  grep -Fq 'Battery saver (settings global low_power): 0' "$environment_before"
  grep -Fq 'Screen brightness setting: 128' "$environment_before"
  foreground_before="$(find "$TMP/output/$flavor" -name 'foreground-before.txt' -print -quit)"
  foreground_after="$(find "$TMP/output/$flavor" -name 'foreground-after.txt' -print -quit)"
  test -s "$foreground_before"
  test -s "$foreground_after"
  grep -Fq 'mCurrentFocus=Window{abc com.android.launcher/.Launcher}' "$foreground_before"
  grep -Fq 'topResumedActivity=ActivityRecord{abc com.android.launcher/.Launcher}' "$foreground_before"
  grep -Fq 'Build fingerprint: fixture/fingerprint' "$environment_before"
  grep -Fq 'Boot completed: 1' "$environment_before"
  grep -Eq '^Device uptime since boot: [0-9]+ seconds$' "$environment_before"
  grep -Fq 'printf "%.0f seconds\n", $1' "$RUNNER_SOURCE" || {
    echo "FAIL: uptime AWK output must use a single newline escape." >&2
    exit 1
  }
  grep -Fq "printf 'unavailable\n'" "$RUNNER_SOURCE" || {
    echo "FAIL: unavailable uptime output must use a single newline escape." >&2
    exit 1
  }

  if FAKE_ENV_DIAGNOSTICS_FAIL=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-diagnostics-fail.log" 2>&1; then
    :
  else
    echo "FAIL: unavailable device diagnostics must not fail a valid Macrobenchmark ($flavor)." >&2
    cat "$TMP/$flavor-diagnostics-fail.log" >&2
    exit 1
  fi
  diagnostic_snapshot="$(find "$TMP/output/$flavor" -name 'environment-after.txt' -print | sort | tail -n 1)"
  grep -Fq 'level: unavailable' "$diagnostic_snapshot"
  grep -Fq 'unavailable' "$diagnostic_snapshot"
  diagnostic_summary="$(dirname "$diagnostic_snapshot")/benchmark-summary.txt"
  grep -Fq 'Thermal status before measurement: unknown' "$diagnostic_summary"
  grep -Fq 'VERDICT: THERMAL STATUS UNKNOWN' "$diagnostic_summary"
  # Moderate-or-higher pre-existing throttling must stop the run before instrumentation.
  rm -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
  : > "$FAKE_COMMAND_LOG"
  if FAKE_THERMAL_STATUS=2 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-thermal-preflight.log" 2>&1; then
    echo "FAIL: phone runner accepted a device already in moderate thermal throttling ($flavor)." >&2
    exit 1
  fi
  grep -Fq 'Thermal status 2 (MODERATE) is already active' "$TMP/$flavor-thermal-preflight.log" || {
    echo "FAIL: thermal preflight did not explain the active throttling state ($flavor)." >&2
    cat "$TMP/$flavor-thermal-preflight.log" >&2
    exit 1
  }
  if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
    echo "FAIL: Macrobenchmark instrumentation started despite moderate-or-higher pre-existing throttling ($flavor)." >&2
    exit 1
  fi

  # Light throttling is explicitly distinct from moderate-or-higher throttling.
  rm -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
  FAKE_THERMAL_STATUS=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-thermal-light.log" 2>&1
  light_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  grep -Fq 'Thermal status before measurement: 1 (LIGHT)' "$light_summary"
  grep -Fq 'NO MODERATE-OR-HIGHER THERMAL STATUS OBSERVED AT THE TWO SNAPSHOTS' "$light_summary"

  # A run that reaches moderate throttling after the final iteration remains saved,
  # but is marked as thermally contaminated and unsuitable for clean comparison.
  rm -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
  FAKE_THERMAL_STATUS=0 FAKE_THERMAL_STATUS_AFTER=2 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-thermal-after.log" 2>&1
  post_thermal_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  grep -Fq 'Thermal status before measurement: 0 (NONE)' "$post_thermal_summary"
  grep -Fq 'Thermal status after measurement: 2 (MODERATE)' "$post_thermal_summary"
  grep -Fq 'VERDICT: THERMALLY CONTAMINATED' "$post_thermal_summary"

  : > "$FAKE_COMMAND_LOG"
  if FAKE_WIFI_DEBUG_PROMPT=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-wifi-prompt.log" 2>&1; then
    echo "FAIL: phone runner must stop before instrumentation when Android's Wi-Fi debugging confirmation is foregrounded ($flavor)." >&2
    exit 1
  fi
  grep -Fq 'Wireless debugging confirmation is active' "$TMP/$flavor-wifi-prompt.log"
  grep -Fq 'com.android.systemui/.wifi.WifiDebuggingActivity' "$TMP/$flavor-wifi-prompt.log"
  if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
    echo "FAIL: instrumentation must not start while the Wi-Fi debugging confirmation is active ($flavor)." >&2
    exit 1
  fi

  if FAKE_NEGATIVE_STATUS_CODE=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-negative-status.log" 2>&1; then
    echo "FAIL: phone runner accepted a negative per-test instrumentation status ($flavor)." >&2
    exit 1
  fi
  grep -Fq "The test reported a failure" "$TMP/$flavor-negative-status.log" || {
    echo "FAIL: negative per-test instrumentation status was not diagnosed ($flavor)." >&2
    cat "$TMP/$flavor-negative-status.log" >&2
    exit 1
  }

  for missing_case in startupNoCompilation startupPartialWithoutBaselineProfile startupWithBaselineProfile; do
    if FAKE_MISSING_TEST_CASE="$missing_case" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-$missing_case.log" 2>&1; then
      echo "FAIL: Macrobenchmark accepted a run missing $missing_case." >&2
      exit 1
    fi
    grep -Fq "did not execute required comparison case '$missing_case'" "$TMP/$flavor-$missing_case.log" || {
      echo "FAIL: missing-case rejection did not identify $missing_case." >&2
      cat "$TMP/$flavor-$missing_case.log" >&2
      exit 1
    }
  done

  for bad_json in empty missing-profile missing-ttfd one-arm-metrics wrong-repeat-iterations wrong-warmup-iterations short-samples invalid-stats wrong-median wrong-cv wrong-art-cv negative-art-run missing-art-jit unpaired-classload missing-cv missing-samples missing-trace-metadata missing-trace-file empty-trace-file; do
    if FAKE_BENCHMARK_JSON="$bad_json" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-$bad_json.log" 2>&1; then
      echo "FAIL: Macrobenchmark accepted incomplete JSON fixture $bad_json." >&2
      exit 1
    fi
    grep -Eq 'Macrobenchmark JSON|benchmarkData.json|Macrobenchmark trace output' "$TMP/$flavor-$bad_json.log" || {
      echo "FAIL: incomplete JSON rejection did not identify the result-data gap ($bad_json)." >&2
      cat "$TMP/$flavor-$bad_json.log" >&2
      exit 1
    }
  done
done

# Verify the API 33 report explicitly identifies its pre-34 ART verification caveat.
# The preceding flavor loop leaves the Play Store variant installed. Restore Full
# explicitly because the phone runner correctly requires a matching installed variant.
# API 33 profile capture and variant installation remain available without root.
if ! FAKE_API=33 sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile full > "$TMP/api33-profile-install.log" 2>&1; then
  echo "FAIL: API 33 should still allow non-minified profile-capture installation without root." >&2
  cat "$TMP/api33-profile-install.log" >&2
  exit 1
fi
if ! FAKE_API=33 sh "$BUNDLE/nuvio-phone-benchmark.sh" collect full > "$TMP/api33-profile-collect.log" 2>&1; then
  echo "FAIL: API 33 should still allow Baseline Profile collection without root." >&2
  cat "$TMP/api33-profile-collect.log" >&2
  exit 1
fi
if ! FAKE_API=33 sh "$BUNDLE/nuvio-phone-benchmark.sh" install full > "$TMP/api33-install.log" 2>&1; then
  echo "FAIL: API 33 fixture could not install the matching Full benchmark variant." >&2
  cat "$TMP/api33-install.log" >&2
  exit 1
fi
# API 33 unrooted Home measurements must fail before instrumentation because
# AndroidX may uninstall/reinstall the app while resetting compilation state.
: > "$FAKE_COMMAND_LOG"
if FAKE_API=33 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure full > "$TMP/api33-unrooted.log" 2>&1; then
  echo "FAIL: API 33 unrooted authenticated Home measurement should be rejected." >&2
  exit 1
fi
grep -Fq 'Authenticated Home measurement on API 33 requires a rooted instrumentation shell' "$TMP/api33-unrooted.log" || {
  echo "FAIL: API 33 preflight did not explain the signed-in app-data reset risk." >&2
  cat "$TMP/api33-unrooted.log" >&2
  exit 1
}
if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
  echo "FAIL: API 33 unrooted rejection happened after instrumentation had already started." >&2
  cat "$FAKE_COMMAND_LOG" >&2
  exit 1
fi

# The same API 33 measurement remains available when the instrumentation shell
# can pass AndroidX's su root id capability check.
: > "$FAKE_COMMAND_LOG"
if ! FAKE_API=33 FAKE_SU_ROOT=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure full > "$TMP/api33-art-caveat.log" 2>&1; then
  echo "FAIL: API 33 measurement should pass when 'su root id' is available." >&2
  cat "$TMP/api33-art-caveat.log" >&2
  exit 1
fi
: > "$FAKE_COMMAND_LOG"
if ! FAKE_API=33 FAKE_SHELL_ROOT=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure full > "$TMP/api33-already-root.log" 2>&1; then
  echo "FAIL: API 33 measurement should also pass when the shell is already root." >&2
  cat "$TMP/api33-already-root.log" >&2
  exit 1
fi
api33_result_dir="$(find "$TMP/output/full" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)"
[ -n "$api33_result_dir" ] || { echo 'FAIL: API 33 report fixture did not produce a measure-* result.' >&2; exit 1; }
for expected in \
  'Android API: 33' \
  'API 33 uses the pre-34 reset path' \
  'ART verification state can differ' \
  'APK sideload via pm install; not Google Play delivery.'; do
  grep -Fq "$expected" "$api33_result_dir/benchmark-summary.txt" || {
    echo "FAIL: API 33 benchmark summary is missing '$expected'." >&2
    cat "$api33_result_dir/benchmark-summary.txt" >&2
    exit 1
  }
done

# Verify the signing-key recovery path uninstalls only the isolated benchmark
# and self-instrumenting test packages, never production com.nuvio.media.
rm -rf "$TMP/output"
export FAKE_INCOMPATIBLE=1 FAKE_INCOMPATIBLE_ONCE="$TMP/incompatible-once"
if ! sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile full >/dev/null; then
  echo "FAIL: signing-key recovery command failed; captured package-manager calls:" >&2
  cat "$FAKE_COMMAND_LOG" >&2
  exit 1
fi
if ! grep -Fq 'pm uninstall com.nuvio.media.benchmark' "$FAKE_COMMAND_LOG"; then
  echo "FAIL: recovery did not uninstall the isolated benchmark package:" >&2
  cat "$FAKE_COMMAND_LOG" >&2
  exit 1
fi
if ! grep -Fq 'pm uninstall com.nuvio.android.baselineprofile' "$FAKE_COMMAND_LOG"; then
  echo "FAIL: recovery did not uninstall the isolated test package:" >&2
  cat "$FAKE_COMMAND_LOG" >&2
  exit 1
fi
if grep -Eq 'pm uninstall com\.nuvio\.media([[:space:]]|$)' "$FAKE_COMMAND_LOG"; then
  echo 'FAIL: signing-key recovery attempted to uninstall production com.nuvio.media.' >&2
  exit 1
fi
unset FAKE_INCOMPATIBLE
grep -q 'monkey -p com.nuvio.media.benchmark 1' "$FAKE_COMMAND_LOG"
grep -Fq "pm install -r $TMP/staged/Nuvio-full-NonMinifiedRelease.apk" "$FAKE_COMMAND_LOG"
test -f "$TMP/staged/Nuvio-full-NonMinifiedRelease.apk"
test -f "$TMP/staged/Nuvio-playstore-BenchmarkRelease-Tests.apk"
test -f "$TMP/staged/Nuvio-full-BenchmarkRelease.apk"
grep -Fq 'com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner' "$FAKE_COMMAND_LOG"
grep -q 'pm list instrumentation' "$FAKE_COMMAND_LOG"

sh -n "$BUNDLE/nuvio-phone-benchmark.sh"
printf 'PASS: phone runner auto-stages only required APK pairs, resolves the self-instrumenting runner, benchmarks the isolated app for both flavors, runs the combined workflow, exports results, rejects unsupported devices/order, and protects production Nuvio.\n'
