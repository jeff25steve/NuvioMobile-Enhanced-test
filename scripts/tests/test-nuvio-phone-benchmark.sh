#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNNER_SOURCE="$SCRIPT_DIR/nuvio-phone-benchmark.sh"
DESKTOP_RUNNER="$SCRIPT_DIR/run-android-startup-profile-benchmark.sh"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
READINESS_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupReadiness.java"
HOME_BROWSE_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/HomeBrowseReadiness.java"

[ -f "$DESKTOP_RUNNER" ] || { echo "FAIL: desktop runner is missing." >&2; exit 1; }
[ -f "$GENERATOR_SOURCE" ] && [ -f "$BENCHMARK_SOURCE" ] && [ -f "$READINESS_SOURCE" ] && [ -f "$HOME_BROWSE_SOURCE" ] || { echo "FAIL: baseline profile or readiness sources are missing." >&2; exit 1; }
sh "$SCRIPT_DIR/tests/test-android-startup-initialization.sh" "$ROOT_DIR"
sh "$SCRIPT_DIR/tests/test-android-startup-readiness.sh"
sh "$SCRIPT_DIR/tests/test-android-ui-automator-stale-retry.sh" "$ROOT_DIR"
sh "$SCRIPT_DIR/tests/test-android-baseline-profile.sh"
sh "$SCRIPT_DIR/tests/test-android-benchmark-run-directory.sh"
sh "$SCRIPT_DIR/tests/test-home-poster-style-propagation.sh" "$ROOT_DIR"
sh "$SCRIPT_DIR/tests/test-library-vertical-row-window.sh" "$ROOT_DIR"
for expected in \
  'The three cold-start comparison arms are:' \
  'partial compilation without a Baseline Profile (3 warmups)' \
  'partial compilation requiring the packaged Baseline Profile' \
  'Compare TTID and TTFD medians and per-iteration traces.' \
  'text rules collected by the separate collect step are not installed into this APK.' \
  'Do not reopen LADB or run shell commands until instrumentation finishes.' \
  'capture_profile_flush_diagnostics() {' \
  'verify_profile_installer_receiver() {' \
  'capture_target_process_snapshot() {' \
  'start_profile_collection_diagnostics() {' \
  'stop_profile_collection_diagnostics() {' \
  'profile-installer-receiver.txt' \
  'profile-process-timeline.txt' \
  'profile-collection-logcat.txt' \
  'ps -A' \
  'pgrep -l -f' \
  'pgrep -l -f -a' \
  'ANDROIDX_PROCESS_PATTERN=' \
  'capture_androidx_process_snapshot() {' \
  'AndroidX-equivalent process lookup:' \
  'awk -v package="$APP_PACKAGE"' \
  "logcat -v time -s 'Benchmark:*' 'ProfileInstaller:*' 'AndroidRuntime:E'" \
  "logcat -d -v time -s 'ProfileInstaller:*' 'Benchmark:*' 'AndroidRuntime:E'" \\
  'profile-flush-diagnostics.txt. These captures help separate a marker timeout, an external foreground interruption and an ART profile-save failure.'; do
  grep -Fq "$expected" "$RUNNER_SOURCE" || {
    echo "FAIL: phone benchmark guidance is missing '$expected'." >&2
    exit 1
  }
done
if grep -Fq 'am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE' "$RUNNER_SOURCE"; then
  echo "FAIL: standalone SAVE_PROFILE preflight must not gate BaselineProfileRule collection." >&2
  exit 1
fi
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
  if [ "$source" = "$GENERATOR_SOURCE" ]; then
    grep -Fq 'StartupReadiness.awaitHomeAndFullyDrawn(device, "startup", scope.getIteration())' "$source" &&
      grep -Fq 'StartupReadiness.awaitHomeAndFullyDrawn(device, "home_browse", scope.getIteration())' "$source" || {
      echo "FAIL: Baseline Profile journeys must label their readiness diagnostics." >&2
      exit 1
    }
  else
    grep -Fq 'StartupReadiness.awaitHomeAndFullyDrawn(device, journeyName, scope.getIteration())' "$source" || {
      echo "FAIL: startup comparison arms must label their readiness diagnostics." >&2
      exit 1
    }
  fi

  if [ "$source" = "$BENCHMARK_SOURCE" ]; then
    grep -Fq '"startupNoCompilation",' "$source" &&
      grep -Fq '"startupPartialWithoutBaselineProfile",' "$source" &&
      grep -Fq '"startupWithBaselineProfile",' "$source" || {
      echo "FAIL: all three comparison cases must retain stable diagnostic journey names." >&2
      exit 1
    }
    grep -Fq 'androidx-benchmark-macro = "1.6.0-alpha02"' "$ROOT_DIR/gradle/libs.versions.toml" || {
      echo "FAIL: Startup Insights usage must be checked against the pinned AndroidX Macrobenchmark API version." >&2
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
    grep -Fq 'new ExperimentalConfig(null, new StartupInsightsConfig(true), null)' "$source" || {
      echo "FAIL: both startup comparison arms must enable trace-based Startup Insights through the AndroidX 1.6.0-alpha02 Java constructor." >&2
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
  'StaleNodeRetry.run(() -> {' \
  'Last package to report an accessibility event' \
  'markerVisible(UiDevice device, String marker)' \
  'HomeScreen marker in target active window:' \
  'Fully-drawn marker in target active window:'; do
  grep -Fq "$expected" "$READINESS_SOURCE" || {
    echo "FAIL: shared startup readiness helper is missing '$expected'." >&2
    exit 1
  }
done
grep -Fq 'StartupReadiness.targetMarkerHasVisibleBounds(device, targetMarker)' "$HOME_BROWSE_SOURCE" || {
  echo "FAIL: Home browse visible-bounds reads must use the stale-node-safe reacquisition helper." >&2
  exit 1
}
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
grep -Fq 'Home has a populated, horizontally scrollable catalogue' "$RUNNER_SOURCE" || {
  echo "FAIL: phone runner must tell users that browse profile collection requires a populated, horizontally scrollable Home catalogue." >&2
  exit 1
}
grep -Fq 'Home contains at least one catalogue shelf with enough entries to scroll horizontally' "$DESKTOP_RUNNER" || {
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
  list)
    case "${FAKE_INSTRUMENTATION_MODE:-valid}" in
      missing) ;;
      wrong)
        echo 'instrumentation:com.nuvio.android.baselineprofile/com.example.WrongRunner (target=com.nuvio.android.baselineprofile)'
        ;;
      ambiguous)
        echo 'instrumentation:com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner (target=com.nuvio.android.baselineprofile)'
        echo 'instrumentation:com.nuvio.android.baselineprofile/com.example.AlternateRunner (target=com.nuvio.android.baselineprofile)'
        ;;
      *)
        echo 'instrumentation:com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner (target=com.nuvio.android.baselineprofile)'
        ;;
    esac
    ;;
  install)
    if [ "${FAKE_INCOMPATIBLE:-0}" = "1" ] && [ "$2" = "-r" ] && [ ! -f "$FAKE_INCOMPATIBLE_ONCE" ]; then
      : > "$FAKE_INCOMPATIBLE_ONCE"
      echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'
      exit 1
    fi
    source_apk=""
    for argument in "$@"; do source_apk="$argument"; done
    case "$source_apk" in
      *-Tests.apk) cp -f "$source_apk" "$FAKE_INSTALLED_DIR/test-base.apk" || exit 1 ;;
      *.apk) cp -f "$source_apk" "$FAKE_INSTALLED_DIR/app-base.apk" || exit 1 ;;
    esac
    echo 'Success'
    ;;
  path)
    case "$2" in
      com.nuvio.media.benchmark)
        if [ "${FAKE_STALE_INSTALLED_PACKAGE:-}" = app ]; then
          printf 'stale installed app APK\n' > "$FAKE_INSTALLED_DIR/stale-app.apk"
          printf 'package:%s\n' "$FAKE_INSTALLED_DIR/stale-app.apk"
        elif [ "${FAKE_UNREADABLE_INSTALLED_PACKAGE:-}" = app ]; then
          printf 'package:%s\n' "$FAKE_INSTALLED_DIR/unreadable-app.apk"
        elif [ "${FAKE_MISSING_INSTALLED_PACKAGE:-}" = app ]; then
          :
        elif [ "${FAKE_SPLIT_INSTALLED_PACKAGE:-}" = app ]; then
          printf 'package:%s\npackage:%s\n' "$FAKE_INSTALLED_DIR/app-base.apk" "$FAKE_INSTALLED_DIR/app-base.apk"
        else printf 'package:%s\n' "$FAKE_INSTALLED_DIR/app-base.apk"; fi
        ;;
      com.nuvio.media.benchmark.child)
        if [ "\${FAKE_INSTALLED_DOTTED_SUBPACKAGE:-0}" = "1" ]; then
          printf 'package:%s\n' "$FAKE_INSTALLED_DIR/dotted-child.apk"
        else
          exit 1
        fi
        ;;
      com.nuvio.android.baselineprofile)
        if [ "${FAKE_STALE_INSTALLED_PACKAGE:-}" = test ]; then
          printf 'stale installed test APK\n' > "$FAKE_INSTALLED_DIR/stale-test.apk"
          printf 'package:%s\n' "$FAKE_INSTALLED_DIR/stale-test.apk"
        elif [ "${FAKE_UNREADABLE_INSTALLED_PACKAGE:-}" = test ]; then
          printf 'package:%s\n' "$FAKE_INSTALLED_DIR/unreadable-test.apk"
        elif [ "${FAKE_MISSING_INSTALLED_PACKAGE:-}" = test ]; then
          :
        elif [ "${FAKE_SPLIT_INSTALLED_PACKAGE:-}" = test ]; then
          printf 'package:%s\npackage:%s\n' "$FAKE_INSTALLED_DIR/test-base.apk" "$FAKE_INSTALLED_DIR/test-base.apk"
        else printf 'package:%s\n' "$FAKE_INSTALLED_DIR/test-base.apk"; fi
        ;;
      *) exit 1 ;;
    esac
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
  *StartupMemoryBenchmark*)
    mkdir -p "$FAKE_MEDIA_DIR"
    printf 'INSTRUMENTATION_STATUS: class=com.nuvio.android.baselineprofile.StartupMemoryBenchmark\n'
    printf 'INSTRUMENTATION_STATUS: test=startupWithBaselineProfileMemoryProfile\n'
    printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
    memory_mode="${FAKE_MEMORY_PROFILE_MODE:-complete}"
    memory_filename="startupWithBaselineProfileMemoryProfile_iter000-memoryProfiling.perfetto-trace"
    memory_type="PerfettoTrace"
    case "$memory_mode" in
      wrong-type) memory_type="MethodTrace" ;;
      wrong-suffix) memory_filename="startupWithBaselineProfileMemoryProfile_iter000-memoryProfiling.trace" ;;
    esac
    printf 'fixture normal timing trace\n' > "$FAKE_MEDIA_DIR/startupWithBaselineProfileMemoryProfile-trace-0.perfetto-trace"
    case "$memory_mode" in
      missing-trace-file) ;;
      empty-trace-file) : > "$FAKE_MEDIA_DIR/$memory_filename" ;;
      *) printf 'fixture pprof heap trace\n' > "$FAKE_MEDIA_DIR/$memory_filename" ;;
    esac
    cat > "$FAKE_MEDIA_DIR/fake-benchmarkData.json" <<JSON
{
  "context": {"build": {"model": "Fixture phone", "version": {"sdk": 35}}, "cpuCoreCount": 8, "cpuLocked": true, "cpuMaxFreqHz": 2800000000, "memTotalBytes": 12884901888, "sustainedPerformanceModeEnabled": false, "artMainlineVersion": 123456789, "osCodenameAbbreviated": "REL"},
  "benchmarks": [{
    "className": "com.nuvio.android.baselineprofile.StartupMemoryBenchmark",
    "name": "startupWithBaselineProfileMemoryProfile",
    "metrics": {
      "timeToInitialDisplayMs": {"minimum": 420, "maximum": 420, "median": 420, "coefficientOfVariation": 0, "runs": [420]},
      "timeToFullDisplayMs": {"minimum": 680, "maximum": 680, "median": 680, "coefficientOfVariation": 0, "runs": [680]},
      "artJitCount": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0]},
      "artJitSumMs": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0]},
      "artVerifyClassCount": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0]},
      "artVerifyClassSumMs": {"minimum": 0, "maximum": 0, "median": 0, "coefficientOfVariation": 0, "runs": [0]}
    },
    "sampledMetrics": {},
    "warmupIterations": 0,
    "repeatIterations": 1,
    "thermalThrottleSleepSeconds": 0,
    "profilerOutputs": [
      {"type": "PerfettoTrace", "label": "Trace Iteration 0", "filename": "startupWithBaselineProfileMemoryProfile-trace-0.perfetto-trace"},
      {"type": "$memory_type", "label": "Memory Profiling Iteration 0", "filename": "$memory_filename"}
    ]
  }]
}
JSON
    case "$memory_mode" in
      wrong-class)
        sed 's/StartupMemoryBenchmark/OtherMemoryBenchmark/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      wrong-iterations)
        sed 's/"repeatIterations": 1/"repeatIterations": 2/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-output)
        awk '
          /Memory Profiling Iteration 0/ { next }
          /Trace Iteration 0/ { sub(/},[[:space:]]*$/, "}") }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      duplicate-output)
        awk '
          /Memory Profiling Iteration 0/ {
            sub(/}[[:space:]]*$/, "},")
            print
            quote = sprintf("%c", 34)
            print "      {" quote "type" quote ": " quote "PerfettoTrace" quote ", " quote "label" quote ": " quote "Memory Profiling Iteration 0" quote ", " quote "filename" quote ": " quote "duplicate-memoryProfiling.perfetto-trace" quote "}"
            next
          }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        printf 'duplicate trace\n' > "$FAKE_MEDIA_DIR/duplicate-memoryProfiling.perfetto-trace"
        ;;
    esac
    ;;
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
        readiness_iteration=0
        while [ "$readiness_iteration" -lt 10 ]; do
          omit_readiness_record=0
          readiness_mode="${FAKE_READINESS_TIMING_MODE:-complete}"
          if [ "$readiness_mode" = missing-last ] &&
             [ "$test_name" = startupWithBaselineProfile ] &&
             [ "$readiness_iteration" -eq 9 ]; then
            omit_readiness_record=1
          fi
          if [ "$omit_readiness_record" -eq 0 ]; then
            home_wait_ms=150
            main_content_wait_ms=200
            if [ "$readiness_mode" = malformed-last ] &&
               [ "$test_name" = startupWithBaselineProfile ] &&
               [ "$readiness_iteration" -eq 9 ]; then
              home_wait_ms=invalid
            fi
            if [ "$readiness_mode" = malformed-main-content-last ] &&
               [ "$test_name" = startupWithBaselineProfile ] &&
               [ "$readiness_iteration" -eq 9 ]; then
              main_content_wait_ms=invalid
            fi
            printf 'INSTRUMENTATION_STATUS: stream=NUVIO_STARTUP_READINESS_TIMING: journey=%s iteration=%s active_window_wait_ms=0 home_marker_wait_ms=%s main_content_marker_wait_ms=%s fully_drawn_marker_wait_ms=200 total_wait_ms=550 outcome=ready\n' \
              "$test_name" "$readiness_iteration" "$home_wait_ms" "$main_content_wait_ms"
          fi
          readiness_iteration=$((readiness_iteration + 1))
        done
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
    if [ "${FAKE_BENCHMARK_JSON:-complete}" = "extra-profiler-output" ]; then
      awk '
        !inserted && /"profilerOutputs": \[/ {
          print
          print "        {\"type\":\"MethodTrace\",\"label\":\"Method Trace\",\"filename\":\"startup-method.trace\"},"
          inserted=1
          next
        }
        { print }
        END { if (!inserted) exit 1 }
      ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
      mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
    fi
    case "${FAKE_BENCHMARK_JSON:-complete}" in
      missing-context-sdk)
        awk '
          /"version": \{"sdk": 35\}/ { sub(/"version": \{"sdk": 35\}/, "\"version\": {}") }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      misleading-global-sdk)
        sed 's/"version": {"sdk": 35}/"version": {}/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" |
          sed 's/"compilationMode": "None"/"sdk": 35, "compilationMode": "None"/' > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      misleading-global-build)
        awk '
          NR == 1 {
            print
            print "  \"build\": {\"model\": \"Decoy phone\", \"version\": {\"sdk\": 35}},"
            print "  \"context\": {},"
            next
          }
          /^  \"context\":/ { next }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      non-object-context)
        awk '
          NR == 1 {
            print
            print "  \"build\": {\"version\": {\"sdk\": 35}},"
            next
          }
          /^  \"context\":/ { print "  \"context\": \"not an object\","; next }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      non-object-build)
        awk '
          /^  \"context\":/ {
            print "  \"context\": {\"build\": \"not an object\", \"cpuCoreCount\": 8, \"cpuLocked\": true, \"cpuMaxFreqHz\": 2800000000, \"memTotalBytes\": 12884901888, \"sustainedPerformanceModeEnabled\": false, \"artMainlineVersion\": 123456789, \"osCodenameAbbreviated\": \"REL\"},"
            next
          }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      non-object-version)
        sed 's/"version": {"sdk": 35}/"version": "35"/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      duplicate-context)
        awk '
          /^  "benchmarks":/ {
            print "  \"context\": {\"build\": {\"model\": \"Decoy phone\", \"version\": {\"sdk\": 35}}},"
          }
          { print }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      nested-metric-fields)
        awk '
          !replaced && /"timeToInitialDisplayMs":/ {
            print "        \"timeToInitialDisplayMs\": {\"details\": {\"minimum\": 400, \"maximum\": 490, \"median\": 445, \"coefficientOfVariation\": 0.068037086608932, \"runs\": [400,410,420,430,440,450,460,470,480,490]}},"
            replaced=1
            next
          }
          { print }
          END { if (!replaced) exit 1 }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      duplicate-metric-field)
        awk '
          !replaced && /"timeToInitialDisplayMs":/ {
            sub(/"minimum": 400,/, "\"minimum\": 400, \"minimum\": 401,")
            replaced=1
          }
          { print }
          END { if (!replaced) exit 1 }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      nested-art-metric-fields)
        awk '
          !replaced && /"artJitCount":/ {
            print "        \"artJitCount\": {\"details\": {\"minimum\": 1, \"maximum\": 10, \"median\": 5.5, \"coefficientOfVariation\": 0.5504818825631803, \"runs\": [1,2,3,4,5,6,7,8,9,10]}},"
            replaced=1
            next
          }
          { print }
          END { if (!replaced) exit 1 }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
    esac

    if [ "${FAKE_BENCHMARK_JSON:-complete}" = "misleading-root-metadata" ]; then
      awk '
        NR == 1 {
          print
          print "  \"diagnostic\": \"brace } and escaped \\\"build\\\" token\","
          print "  \"model\": \"Root decoy phone\", \"sdk\": 28, \"cpuCoreCount\": 2, \"cpuLocked\": false, \"cpuMaxFreqHz\": 1000000000, \"memTotalBytes\": 1, \"artMainlineVersion\": 1, \"sustainedPerformanceModeEnabled\": true, \"osCodenameAbbreviated\": \"DECOY\","
          next
        }
        { print }
      ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
      mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
    fi
    if [ "${FAKE_BENCHMARK_JSON:-complete}" = "nested-context-metadata" ]; then
      awk '
        /^  "context":/ {
          print "  \"context\": {\"build\": {\"model\": \"Fixture phone\", \"version\": {\"sdk\": 35}}, \"metadata\": {\"cpuCoreCount\": 2, \"cpuLocked\": false, \"cpuMaxFreqHz\": 1000000000, \"memTotalBytes\": 1, \"sustainedPerformanceModeEnabled\": true, \"artMainlineVersion\": 1, \"osCodenameAbbreviated\": \"DECOY\"}},"
          next
        }
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
      complete|misleading-root-metadata|nested-context-metadata|missing-context-sdk|misleading-global-sdk|misleading-global-build|non-object-context|non-object-build|non-object-version|duplicate-context|nested-metric-fields|duplicate-metric-field|nested-art-metric-fields|missing-trace-metadata|missing-trace-file|empty-trace-file|no-classload|extra-profiler-output) ;;
      empty) printf '{"benchmarks":[]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json" ;;
      missing-profile)
        printf '{"benchmarks":[{"name":"startupNoCompilation","metrics":{"timeToInitialDisplayMs":{"median":1},"timeToFullDisplayMs":{"median":2}}}]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-ttfd)
        printf '{"benchmarks":[{"name":"startupNoCompilation","metrics":{"timeToInitialDisplayMs":{"median":1}}},{"name":"startupWithBaselineProfile","metrics":{"timeToInitialDisplayMs":{"median":1}}}]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      missing-one-ttfd-arm)
        awk '
          /"name": "startupWithBaselineProfile"/ { in_profile=1 }
          in_profile && /"timeToFullDisplayMs":/ { next }
          { print }
          in_profile && /"profilerOutputs":/ { in_profile=0 }
        ' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
        ;;
      short-ttfd-samples)
        sed 's/730,740/730/' "$FAKE_MEDIA_DIR/fake-benchmarkData.json" > "$FAKE_MEDIA_DIR/result.tmp"
        mv "$FAKE_MEDIA_DIR/result.tmp" "$FAKE_MEDIA_DIR/fake-benchmarkData.json"
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
    if [ "${FAKE_DUPLICATE_BENCHMARK_JSON:-0}" = "1" ]; then
      cp "$FAKE_MEDIA_DIR/fake-benchmarkData.json" "$FAKE_MEDIA_DIR/second-benchmarkData.json"
    fi
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
        printf 'Lcom/nuvio/Foo;\nHSPLcom/nuvio/Foo;->bar()V\nHSPLcom/nuvio/Foo;->bar()V\n' > "$FAKE_MEDIA_DIR/startup-startup-prof.txt"
      fi
    fi
    if [ "${FAKE_MISSING_HOME_BROWSE_PROFILE:-0}" != "1" ]; then
      if [ "${FAKE_EMPTY_HOME_BROWSE_PROFILE:-0}" = "1" ]; then
        : > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
      elif [ "${FAKE_BLANK_HOME_BROWSE_PROFILE:-0}" = "1" ]; then
        printf '  \n\t\n' > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
      else
        printf 'Lcom/nuvio/HomeCatalog;\nSPLcom/nuvio/HomeCatalog;->scroll()V\n' > "$FAKE_MEDIA_DIR/home_browse-baseline-prof.txt"
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
  package)
    printf 'Package: %s\nReceiver: androidx.profileinstaller.ProfileInstallReceiver\n' "$2"
    ;;
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
  global:low_power)
    if [ -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed" ]; then
      echo "${FAKE_BATTERY_SAVER_AFTER:-${FAKE_BATTERY_SAVER:-0}}"
    else
      echo "${FAKE_BATTERY_SAVER:-0}"
    fi
    ;;
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
cat > "$TMP/bin/ps" <<'STUB'
#!/bin/sh
printf 'u0_a123 12345 1 123456 12345 S com.nuvio.media.benchmark\n'
STUB
cat > "$TMP/bin/pgrep" <<'STUB'
#!/bin/sh
printf 'pgrep %s\n' "$*" >> "$FAKE_COMMAND_LOG"
[ "$1" = "-l" ] && [ "$2" = "-f" ] || exit 2
if [ "$3" = "-a" ]; then
  [ -n "$4" ] || exit 2
else
  [ -n "$3" ] || exit 2
fi
case "${FAKE_PGREP_MODE:-match}" in
  missing) exit 1 ;;
  error) echo 'pgrep fixture error' >&2; exit 2 ;;
  *) printf '12345 com.nuvio.media.benchmark\n12346 com.nuvio.media.benchmark:remote\n12347 com.nuvio.media.benchmark.child.proc:remote\n' ;;
esac
STUB
cat > "$TMP/bin/logcat" <<'STUB'
#!/bin/sh
printf '11-22 02:00:00.000 D Benchmark: Saving profiles for process com.nuvio.media.benchmark\n'
STUB
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:$PATH"
export FAKE_COMMAND_LOG="$TMP/commands.log" FAKE_MEDIA_ROOT="$TMP/media"
export FAKE_MEDIA_DIR="$FAKE_MEDIA_ROOT/com.nuvio.android.baselineprofile"
export FAKE_INSTALLED_DIR="$TMP/installed"
mkdir -p "$FAKE_INSTALLED_DIR"
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
  install_provenance="$TMP/output/current-install-provenance.txt"
  test -s "$install_provenance" || {
    echo "FAIL: install must persist the APK source identities across LADB invocations ($flavor)." >&2
    exit 1
  }
  app_profile_hash="$(sha256sum "$BUNDLE/Nuvio-$flavor-NonMinifiedRelease.apk" | awk '{ print $1 }')"
  test_profile_hash="$(sha256sum "$BUNDLE/Nuvio-$flavor-NonMinifiedRelease-Tests.apk" | awk '{ print $1 }')"
  grep -Fq "Variant: install-profile:$flavor" "$install_provenance"
  grep -Fq "App APK source filename: Nuvio-$flavor-NonMinifiedRelease.apk" "$install_provenance"
  grep -Fq "App APK source SHA-256: $app_profile_hash" "$install_provenance"
  grep -Fq "Test APK source filename: Nuvio-$flavor-NonMinifiedRelease-Tests.apk" "$install_provenance"
  grep -Fq "Test APK source SHA-256: $test_profile_hash" "$install_provenance"
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
  benchmark_provenance="$benchmark_result_dir/provenance.txt"
  test -s "$benchmark_provenance"
  grep -Fq "App APK source filename: Nuvio-$flavor-BenchmarkRelease.apk" "$benchmark_provenance" || {
    echo "FAIL: measurement lost the installed BenchmarkRelease source APK identity ($flavor)." >&2
    cat "$benchmark_provenance" >&2
    exit 1
  }
  grep -Fq "Test APK source filename: Nuvio-$flavor-BenchmarkRelease-Tests.apk" "$benchmark_provenance"
  grep -Fq 'App installed APK verification: MATCH' "$benchmark_provenance"
  grep -Fq 'Test installed APK verification: MATCH' "$benchmark_provenance"
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
  grep -Fq 'Exploratory bootstrap intervals (difference in medians; 10,000 resamples)' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq '95% percentile interval' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'do not correct systematic test-order, device-heating, or time-drift bias' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'three JUnit arms execute as grouped test blocks, not an interleaved rotation' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'Per-iteration samples and matching Perfetto traces' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'UIAutomator readiness observations after startActivityAndWait' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'These stage timings are diagnostic only; they are not TTID, TTFD, or total launch time.' "$benchmark_result_dir/benchmark-summary.txt"
  [ "$(grep -c '  NUVIO_STARTUP_READINESS_TIMING:' "$benchmark_result_dir/benchmark-summary.txt")" -eq 30 ] || {
    echo "FAIL: benchmark summary must retain all thirty per-launch readiness observations ($flavor)." >&2
    exit 1
  }
  grep -Fq 'NUVIO_STARTUP_READINESS_TIMING: journey=startupNoCompilation iteration=0 active_window_wait_ms=0 home_marker_wait_ms=150 main_content_marker_wait_ms=200 fully_drawn_marker_wait_ms=200 total_wait_ms=550 outcome=ready' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'NUVIO_STARTUP_READINESS_TIMING: journey=startupPartialWithoutBaselineProfile iteration=9 active_window_wait_ms=0 home_marker_wait_ms=150 main_content_marker_wait_ms=200 fully_drawn_marker_wait_ms=200 total_wait_ms=550 outcome=ready' "$benchmark_result_dir/benchmark-summary.txt"
  grep -Fq 'NUVIO_STARTUP_READINESS_TIMING: journey=startupWithBaselineProfile iteration=9 active_window_wait_ms=0 home_marker_wait_ms=150 main_content_marker_wait_ms=200 fully_drawn_marker_wait_ms=200 total_wait_ms=550 outcome=ready' "$benchmark_result_dir/benchmark-summary.txt"

  for readiness_mode in missing-last malformed-last malformed-main-content-last; do
    if FAKE_READINESS_TIMING_MODE="$readiness_mode" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-readiness-$readiness_mode.log" 2>&1; then
      echo "FAIL: phone runner accepted incomplete or malformed per-launch readiness diagnostics ($flavor, $readiness_mode)." >&2
      exit 1
    fi
    grep -Fq 'Startup readiness timing records must contain exactly thirty valid per-launch samples' "$TMP/$flavor-readiness-$readiness_mode.log" || {
      echo "FAIL: readiness timing rejection did not identify the missing or malformed records ($flavor, $readiness_mode)." >&2
      cat "$TMP/$flavor-readiness-$readiness_mode.log" >&2
      exit 1
    }
  done


  # The optional heap diagnostic must stay separate from the clean ten-iteration timing run.
  if ! sh "$BUNDLE/nuvio-phone-benchmark.sh" memory-profile "$flavor" > "$TMP/$flavor-memory-profile.log" 2>&1; then
    echo "FAIL: isolated ART/native memory-profile diagnostic should succeed for $flavor." >&2
    cat "$TMP/$flavor-memory-profile.log" >&2
    exit 1
  fi
  memory_result_dir="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'memory-profile-*' -print | sort | tail -n 1)"
  [ -n "$memory_result_dir" ] || { echo "FAIL: memory-profile action must use a dedicated result directory ($flavor)." >&2; exit 1; }
  test -s "$memory_result_dir/fake-benchmarkData.json"
  test -s "$memory_result_dir/startupWithBaselineProfileMemoryProfile_iter000-memoryProfiling.perfetto-trace"
  test -s "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'ART heap sampling: enabled (com.android.art)' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Native heap sampling: enabled (libc.malloc)' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Allocation sampling interval: 2048 bytes' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Continuous dump interval: 500 ms' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Allocation evidence: sampled call stacks, not exact heap totals or RSS/PSS' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Heapprofd behavior: block_client=true; completeness prioritized over frame timing' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'some earliest Zygote-specialization allocations may precede profiling' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'not a statistical startup comparison' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'Memory Profiling Iteration 0' "$memory_result_dir/memory-profile-summary.txt"
  grep -Fq 'memory-profile-summary.txt' "$memory_result_dir/copied-files.txt"

  for memory_fixture in wrong-class wrong-iterations missing-output duplicate-output wrong-type wrong-suffix missing-trace-file empty-trace-file; do
    if FAKE_MEMORY_PROFILE_MODE="$memory_fixture" sh "$BUNDLE/nuvio-phone-benchmark.sh" memory-profile "$flavor" > "$TMP/$flavor-memory-$memory_fixture.log" 2>&1; then
      echo "FAIL: memory-profile accepted invalid profiler output fixture '$memory_fixture' ($flavor)." >&2
      exit 1
    fi
    grep -Fq 'Memory-profile result' "$TMP/$flavor-memory-$memory_fixture.log" || {
      echo "FAIL: invalid memory-profile output was not diagnosed ($memory_fixture, $flavor)." >&2
      cat "$TMP/$flavor-memory-$memory_fixture.log" >&2
      exit 1
    }
  done

  if ! FAKE_BENCHMARK_JSON=misleading-root-metadata sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-root-metadata-decoys.log" 2>&1; then
    echo "FAIL: valid context metadata should still produce a summary when unrelated root metadata is present ($flavor)." >&2
    cat "$TMP/$flavor-root-metadata-decoys.log" >&2
    exit 1
  fi
  decoy_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  for context_value in \
    'Device: Fixture phone | Android API: 35' \
    'CPU: 8 cores | max frequency 2800000000 Hz | clocks locked: true' \
    'Memory: 12884901888 bytes | ART Mainline: 123456789 | sustained performance mode: false' \
    'Android release codename: REL'; do
    grep -Fq "$context_value" "$decoy_summary" || {
      echo "FAIL: summary did not retain context-owned metadata '$context_value' when root decoys were present ($flavor)." >&2
      cat "$decoy_summary" >&2
      exit 1
    }
  done
  for decoy_value in 'Root decoy phone' 'Android API: 28' 'CPU: 2 cores' '1000000000 Hz' '1 bytes' 'ART Mainline: 1 |' 'sustained performance mode: true' 'Android release codename: DECOY'; do
    if grep -Fq "$decoy_value" "$decoy_summary"; then
      echo "FAIL: summary accepted unrelated root-level metadata '$decoy_value' ($flavor)." >&2
      cat "$decoy_summary" >&2
      exit 1
    fi
  done
  if ! FAKE_BENCHMARK_JSON=nested-context-metadata sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-nested-context-metadata.log" 2>&1; then
    echo "FAIL: nested metadata decoys should not prevent a report with valid direct context metadata ($flavor)." >&2
    cat "$TMP/$flavor-nested-context-metadata.log" >&2
    exit 1
  fi
  nested_context_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  for expected in \
    'Device: Fixture phone | Android API: 35' \
    'CPU: not reported cores | max frequency not reported Hz | clocks locked: not reported' \
    'Memory: not reported bytes | ART Mainline: not reported | sustained performance mode: not reported' \
    'Android release codename: not reported'; do
    grep -Fq "$expected" "$nested_context_summary" || {
      echo "FAIL: summary used a nested decoy instead of the direct AndroidX context member '$expected' ($flavor)." >&2
      cat "$nested_context_summary" >&2
      exit 1
    }
  done
  for decoy_value in 'CPU: 2 cores' '1000000000 Hz' '1 bytes' 'ART Mainline: 1 |' 'sustained performance mode: true' 'Android release codename: DECOY'; do
    if grep -Fq "$decoy_value" "$nested_context_summary"; then
      echo "FAIL: summary accepted nested context metadata '$decoy_value' ($flavor)." >&2
      cat "$nested_context_summary" >&2
      exit 1
    fi
  done
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
  profile_provenance="$profile_result_dir/provenance.txt"
  test -s "$profile_provenance"
  grep -Fq "App APK source filename: Nuvio-$flavor-NonMinifiedRelease.apk" "$profile_provenance" || {
    echo "FAIL: profile collection lost the installed NonMinifiedRelease source APK identity ($flavor)." >&2
    cat "$profile_provenance" >&2
    exit 1
  }
  grep -Fq "Test APK source filename: Nuvio-$flavor-NonMinifiedRelease-Tests.apk" "$profile_provenance"
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
  profile_audit="$profile_result_dir/profile-audit-summary.txt"
  test -s "$profile_audit"
  grep -Fq 'Startup Profile' "$profile_audit"
  grep -Fq 'Home browse Baseline Profile' "$profile_audit"
  grep -Fq 'Nonblank rules: 3' "$profile_audit"
  grep -Fq 'Unique exact rules: 2' "$profile_audit"
  grep -Fq 'Duplicate exact rules: 1' "$profile_audit"
  grep -Fq 'Rules carrying H/S/P flags: H=2 S=2 P=2' "$profile_audit"
  grep -Fq 'profile-audit-summary.txt' "$profile_result_dir/copied-files.txt"
  test -n "$(find "$TMP/output/$flavor" -name '*benchmarkData.json' -print -quit)"
  environment_before="$memory_result_dir/environment-before.txt"
  environment_after="$memory_result_dir/environment-after.txt"
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
  memory_copied_files="$(dirname "$environment_before")/copied-files.txt"
  grep -Fq 'thermal-integrity.txt' "$memory_copied_files" || {
    echo "FAIL: thermal integrity report is missing from the memory-profile copied-files manifest ($flavor)." >&2
    cat "$memory_copied_files" >&2
    echo "Memory-profile summary:" >&2
    cat "$(dirname "$environment_before")/memory-profile-summary.txt" >&2
    exit 1
  }
  grep -Fq 'Thermal integrity' "$memory_result_dir/memory-profile-summary.txt" || {
    echo "FAIL: memory-profile summary must include thermal integrity ($flavor)." >&2
    cat "$memory_result_dir/memory-profile-summary.txt" >&2
    exit 1
  }
  grep -Fq 'Battery saver (settings global low_power): 0' "$environment_before"
  grep -Fq 'Screen brightness setting: 128' "$environment_before"
  foreground_before="$(find "$TMP/output/$flavor" -name 'foreground-before.txt' -print -quit)"
  foreground_after="$(find "$TMP/output/$flavor" -name 'foreground-after.txt' -print -quit)"
  test -s "$foreground_before"
  test -s "$foreground_after"
  grep -Fq 'mCurrentFocus=Window{abc com.android.launcher/.Launcher}' "$foreground_before" || {
    echo "FAIL: foreground snapshot is missing the expected launcher mCurrentFocus record ($flavor)." >&2
    cat "$foreground_before" >&2
    exit 1
  }
  grep -Fq 'topResumedActivity=ActivityRecord{abc com.android.launcher/.Launcher}' "$foreground_before" || {
    echo "FAIL: foreground snapshot is missing the expected launcher topResumedActivity record ($flavor)." >&2
    cat "$foreground_before" >&2
    exit 1
  }
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
  diagnostic_snapshot="$(find "$TMP/output/$flavor" -mindepth 2 -maxdepth 2 -type f -path '*/measure-*/environment-after.txt' -print | sort | tail -n 1)"
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

  # Low-power mode is a known performance-affecting device condition.
  : > "$FAKE_COMMAND_LOG"
  if FAKE_BATTERY_SAVER=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-battery-saver-preflight.log" 2>&1; then
    echo "FAIL: Macrobenchmark must reject an enabled Battery Saver state ($flavor)." >&2
    exit 1
  fi
  grep -Fq 'Battery Saver is enabled before Macrobenchmark' "$TMP/$flavor-battery-saver-preflight.log" || {
    echo "FAIL: Battery Saver preflight did not explain the low-power measurement conflict ($flavor)." >&2
    cat "$TMP/$flavor-battery-saver-preflight.log" >&2
    exit 1
  }
  if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
    echo "FAIL: instrumentation started while Battery Saver was enabled ($flavor)." >&2
    exit 1
  fi

  # A state change after measurement is retained in the report, not silently qualified as clean.
  rm -f "$FAKE_MEDIA_DIR/.macrobenchmark-completed"
  FAKE_BATTERY_SAVER=0 FAKE_BATTERY_SAVER_AFTER=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-battery-saver-after.log" 2>&1
  battery_saver_summary="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)/benchmark-summary.txt"
  grep -Fq 'Battery Saver state before measurement: 0 (OFF)' "$battery_saver_summary"
  grep -Fq 'Battery Saver state after measurement: 1 (ON)' "$battery_saver_summary"
  grep -Fq 'VERDICT: BATTERY-SAVER CONTAMINATED' "$battery_saver_summary"

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

  # The package manager must expose one exact self-instrumenting AndroidJUnitRunner.
  for instrumentation_mode in missing wrong ambiguous; do
    : > "$FAKE_COMMAND_LOG"
    if FAKE_INSTRUMENTATION_MODE="$instrumentation_mode" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-instrumentation-$instrumentation_mode.log" 2>&1; then
      echo "FAIL: phone runner accepted $instrumentation_mode self-instrumentation metadata ($flavor)." >&2
      exit 1
    fi
    case "$instrumentation_mode" in
      wrong)
        grep -Fq "Unexpected instrumentation runner" "$TMP/$flavor-instrumentation-$instrumentation_mode.log"
        ;;
      *)
        grep -Fq "Expected exactly one self-instrumenting instrumentation component" "$TMP/$flavor-instrumentation-$instrumentation_mode.log"
        ;;
    esac
    if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
      echo "FAIL: instrumentation started with $instrumentation_mode self-instrumentation metadata ($flavor)." >&2
      exit 1
    fi
  done

  for stale_package in app test; do
    : > "$FAKE_COMMAND_LOG"
    if FAKE_STALE_INSTALLED_PACKAGE="$stale_package" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-stale-$stale_package.log" 2>&1; then
      echo "FAIL: runner accepted a stale installed $stale_package APK ($flavor)." >&2
      exit 1
    fi
    grep -Fq "Installed $stale_package APK SHA-256 does not match the saved source APK" "$TMP/$flavor-stale-$stale_package.log" || {
      echo "FAIL: stale installed $stale_package APK was not identified ($flavor)." >&2
      cat "$TMP/$flavor-stale-$stale_package.log" >&2
      exit 1
    }
    if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
      echo "FAIL: instrumentation started with a mismatched installed $stale_package APK ($flavor)." >&2
      exit 1
    fi
  done
  : > "$FAKE_COMMAND_LOG"
  FAKE_UNREADABLE_INSTALLED_PACKAGE=app sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-unreadable-installed.log" 2>&1
  unreadable_dir="$(find "$TMP/output/$flavor" -mindepth 1 -maxdepth 1 -type d -name 'measure-*' -print | sort | tail -n 1)"
  for invalid_path_state in missing split; do
    : > "$FAKE_COMMAND_LOG"
    if [ "$invalid_path_state" = missing ]; then
      if FAKE_MISSING_INSTALLED_PACKAGE=app sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-missing-installed-path.log" 2>&1; then
        echo "FAIL: runner accepted an installed app with no pm path result ($flavor)." >&2
        exit 1
      fi
      grep -Fq 'Android did not return exactly one installed APK path for app package' "$TMP/$flavor-missing-installed-path.log"
    else
      if FAKE_SPLIT_INSTALLED_PACKAGE=app sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-split-installed-path.log" 2>&1; then
        echo "FAIL: runner accepted a split-installed app although the bundle contract supplies one standalone APK ($flavor)." >&2
        exit 1
      fi
      grep -Fq 'exposes multiple APK paths' "$TMP/$flavor-split-installed-path.log"
    fi
    if grep -Fq 'am instrument' "$FAKE_COMMAND_LOG"; then
      echo "FAIL: instrumentation started without an exact single installed app APK path ($flavor)." >&2
      exit 1
    fi
  done
  grep -Fq 'App installed APK verification: UNVERIFIED' "$unreadable_dir/provenance.txt" || {
    echo "FAIL: unreadable installed APK path must be labelled UNVERIFIED ($flavor)." >&2
    cat "$unreadable_dir/provenance.txt" >&2
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

  for bad_json in empty missing-profile missing-ttfd missing-one-ttfd-arm short-ttfd-samples missing-context-sdk misleading-global-sdk misleading-global-build non-object-context non-object-build non-object-version duplicate-context nested-metric-fields duplicate-metric-field nested-art-metric-fields one-arm-metrics wrong-repeat-iterations wrong-warmup-iterations short-samples invalid-stats wrong-median wrong-cv wrong-art-cv negative-art-run missing-art-jit unpaired-classload missing-cv missing-samples missing-trace-metadata missing-trace-file empty-trace-file extra-profiler-output; do
    if FAKE_BENCHMARK_JSON="$bad_json" sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-$bad_json.log" 2>&1; then
      echo "FAIL: Macrobenchmark accepted incomplete JSON fixture $bad_json." >&2
      exit 1
    fi
    grep -Eq 'Macrobenchmark JSON|benchmarkData.json|Macrobenchmark trace output|Could not create the human-readable Macrobenchmark summary' "$TMP/$flavor-$bad_json.log" || {
      echo "FAIL: incomplete JSON rejection did not identify the result-data gap ($bad_json)." >&2
      cat "$TMP/$flavor-$bad_json.log" >&2
      exit 1
    }
    if [ "$bad_json" = extra-profiler-output ]; then
      grep -Fq 'additional profiler artifacts are not accepted in a clean timing comparison' "$TMP/$flavor-$bad_json.log" || {
        echo "FAIL: additional profiler output was not diagnosed as outside the clean timing contract ($flavor)." >&2
        cat "$TMP/$flavor-$bad_json.log" >&2
        exit 1
      }
    fi
    case "$bad_json" in
      missing-context-sdk|misleading-global-sdk|misleading-global-build|non-object-context|non-object-build|non-object-version|duplicate-context)
        grep -Fq 'context.build.version.sdk as Android API 33 or newer' "$TMP/$flavor-$bad_json.log" || {
          echo "FAIL: SDK metadata corruption was not rejected at the exact context path ($bad_json, $flavor)." >&2
          cat "$TMP/$flavor-$bad_json.log" >&2
          exit 1
        }
        if [ "$bad_json" = misleading-global-sdk ] || [ "$bad_json" = misleading-global-build ] || [ "$bad_json" = non-object-context ]; then
          grep -Fq 'top-level or unrelated metadata fields are ignored' "$TMP/$flavor-$bad_json.log" || {
            echo "FAIL: misleading SDK/build fixture did not identify the exact context metadata rule ($flavor)." >&2
            cat "$TMP/$flavor-$bad_json.log" >&2
            exit 1
          }
        fi
        ;;
    esac
  done

  : > "$FAKE_COMMAND_LOG"
  if FAKE_DUPLICATE_BENCHMARK_JSON=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" > "$TMP/$flavor-multiple-json-files.log" 2>&1; then
    echo "FAIL: phone runner accepted multiple Macrobenchmark JSON result files for one instrumentation run ($flavor)." >&2
    exit 1
  fi
  grep -Fq 'Expected exactly one Macrobenchmark benchmarkData.json file for this instrumentation run; found 2' "$TMP/$flavor-multiple-json-files.log" || {
    echo "FAIL: ambiguous Macrobenchmark result files were not diagnosed ($flavor)." >&2
    cat "$TMP/$flavor-multiple-json-files.log" >&2
    exit 1
  }
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
profile_result_dir="$(find "$TMP/output/full" -mindepth 1 -maxdepth 1 -type d -name 'collect-*' -print | sort | tail -n 1)"
[ -n "$profile_result_dir" ] || { echo 'FAIL: Baseline Profile fixture did not produce a collect-* result.' >&2; exit 1; }
for expected in 'androidx.profileinstaller.ProfileInstallReceiver' 'com.nuvio.media.benchmark'; do
  grep -Fq "$expected" "$profile_result_dir/profile-installer-receiver.txt" "$profile_result_dir/profile-process-timeline.txt" || {
    echo "FAIL: profile collection evidence is missing '$expected'." >&2
    exit 1
  }
done
grep -Fq "AndroidX-equivalent process lookup: pgrep -l -f 'com\\.nuvio\\.media\\.benchmark'" "$profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: exact AndroidX-equivalent pgrep command was not retained.' >&2
  exit 1
}
grep -Fq '12345 com.nuvio.media.benchmark' "$profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: raw pgrep process output was not retained.' >&2
  exit 1
}
grep -Fq 'ANDROIDX_ACCEPTED pid=12345 processName=com.nuvio.media.benchmark' "$profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: pgrep output was not parsed using AndroidX process-name matching.' >&2
  exit 1
}
grep -Fq 'ANDROIDX_ACCEPTED pid=12346 processName=com.nuvio.media.benchmark:remote' "$profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: AndroidX colon-suffix child processes were not accepted.' >&2
  exit 1
}
grep -Fq 'ANDROIDX_ACCEPTED_UNINSTALLED_DOTTED_CHILD pid=12347 processName=com.nuvio.media.benchmark.child.proc:remote' "$profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: AndroidX should accept a dotted custom process when no intermediate or full subpackage is installed.' >&2
  exit 1
}
grep -Fq 'pgrep -l -f com\.nuvio\.media\.benchmark' "$FAKE_COMMAND_LOG" || {
  echo 'FAIL: diagnostic did not invoke AndroidX-equivalent pgrep arguments.' >&2
  exit 1
}
if ! FAKE_API=36 sh "$BUNDLE/nuvio-phone-benchmark.sh" collect full > "$TMP/api36-profile-collect.log" 2>&1; then
  echo "FAIL: API 36 should collect profiles using AndroidX's -a pgrep flag." >&2
  cat "$TMP/api36-profile-collect.log" >&2
  exit 1
fi
api36_profile_result_dir="$(find "$TMP/output/full" -mindepth 1 -maxdepth 1 -type d -name 'collect-*' -print | sort | tail -n 1)"
[ -n "$api36_profile_result_dir" ] || { echo 'FAIL: API 36 Baseline Profile fixture did not produce a collect-* result.' >&2; exit 1; }
grep -Fq "AndroidX-equivalent process lookup: pgrep -l -f -a 'com\.nuvio\.media\.benchmark'" "$api36_profile_result_dir/profile-process-timeline.txt" || {
  echo 'FAIL: API 36 diagnostic did not use AndroidX pgrep -a compatibility flag.' >&2
  exit 1
}
grep -Fq 'pgrep -l -f -a com\.nuvio\.media\.benchmark' "$FAKE_COMMAND_LOG" || {
  echo 'FAIL: API 36 pgrep invocation did not include -a.' >&2
  exit 1
}
if ! FAKE_API=36 FAKE_PGREP_MODE=missing sh "$BUNDLE/nuvio-phone-benchmark.sh" collect full > "$TMP/api36-no-pgrep-match.log" 2>&1; then
  echo "FAIL: a zero-process diagnostic sample should be recorded without aborting profile collection." >&2
  cat "$TMP/api36-no-pgrep-match.log" >&2
  exit 1
fi
api36_no_match_dir="$(find "$TMP/output/full" -mindepth 1 -maxdepth 1 -type d -name 'collect-*' -print | sort | tail -n 1)"
[ -n "$api36_no_match_dir" ] || { echo 'FAIL: API 36 no-match fixture did not produce a collect-* result.' >&2; exit 1; }
grep -Fq 'No matching process (pgrep exit status 1).' "$api36_no_match_dir/profile-process-timeline.txt" || {
  echo 'FAIL: zero-process pgrep result was not distinguished from a command error.' >&2
  exit 1
}
grep -Fq 'com.nuvio.media.benchmark' "$api36_no_match_dir/profile-process-timeline.txt" || {
  echo 'FAIL: independent ps snapshot was not retained alongside the pgrep no-match result.' >&2
  exit 1
}
if ! FAKE_API=33 FAKE_INSTALLED_DOTTED_SUBPACKAGE=1 sh "$BUNDLE/nuvio-phone-benchmark.sh" collect full > "$TMP/installed-dotted-child.log" 2>&1; then
  echo 'FAIL: dotted-subpackage filtering diagnostic collection failed.' >&2
  cat "$TMP/installed-dotted-child.log" >&2
  exit 1
fi
installed_dotted_dir="$(find "$TMP/output/full" -mindepth 1 -maxdepth 1 -type d -name 'collect-*' -print | sort | tail -n 1)"
grep -Fq 'ANDROIDX_REJECTED_INSTALLED_SUBPACKAGE pid=12347 processName=com.nuvio.media.benchmark.child.proc:remote' "$installed_dotted_dir/profile-process-timeline.txt" || {
  echo 'FAIL: AndroidX dotted child was not rejected when an intermediate package prefix is installed.' >&2
  exit 1
}
grep -Fq 'Saving profiles for process com.nuvio.media.benchmark' "$profile_result_dir/profile-collection-logcat.txt" || {
  echo 'FAIL: live AndroidX logcat evidence was not retained during profile collection.' >&2
  exit 1
}
for expected in 'Installed receiver evidence -> ' 'Live target-process timeline -> ' 'Live AndroidX/ProfileInstaller logcat -> '; do
  grep -Fq "$expected" "$profile_result_dir/copied-files.txt" || {
    echo "FAIL: copied-files manifest is missing '$expected'." >&2
    exit 1
  }
done
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
