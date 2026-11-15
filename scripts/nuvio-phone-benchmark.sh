#!/system/bin/sh
# Phone-only Nuvio startup benchmark runner for LADB / Android shell.
# Keep this script and all eight arm64-v8a APKs in the same Downloads folder.
set -eu

APP_PACKAGE="com.nuvio.media.benchmark"
TEST_PACKAGE="com.nuvio.android.baselineprofile"
GENERATOR_CLASS="com.nuvio.android.baselineprofile.BaselineProfileGenerator"
BENCHMARK_CLASS="com.nuvio.android.baselineprofile.StartupBenchmark"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
STAGING_DIR="${NUVIO_PHONE_STAGE_DIR:-/data/local/tmp/NuvioBenchmark}"
OUTPUT_ROOT="${NUVIO_PHONE_OUTPUT_DIR:-/sdcard/Download/NuvioStartupBenchmark}"
MEDIA_ROOT="${NUVIO_PHONE_MEDIA_ROOT:-/sdcard/Android/media}"

fail() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

# Android's package manager cannot reliably install APKs directly from shared
# Downloads storage. Stage the complete bundle in shell-readable local storage
# automatically, so the user never has to copy files manually.
if [ "$SCRIPT_DIR" != "$STAGING_DIR" ]; then
  mkdir -p "$STAGING_DIR" || fail "Cannot create the temporary benchmark folder: $STAGING_DIR"
  case "${1:-}:${2:-}" in
    install-profile:full)
      STAGE_APKS="Nuvio-full-NonMinifiedRelease.apk Nuvio-full-NonMinifiedRelease-Tests.apk"
      ;;
    install-profile:playstore)
      STAGE_APKS="Nuvio-playstore-NonMinifiedRelease.apk Nuvio-playstore-NonMinifiedRelease-Tests.apk"
      ;;
    run:full|install:full)
      STAGE_APKS="Nuvio-full-BenchmarkRelease.apk Nuvio-full-BenchmarkRelease-Tests.apk"
      ;;
    run:playstore|install:playstore)
      STAGE_APKS="Nuvio-playstore-BenchmarkRelease.apk Nuvio-playstore-BenchmarkRelease-Tests.apk"
      ;;
    *)
      STAGE_APKS=""
      ;;
  esac
  for apk in $STAGE_APKS; do
    [ -f "$SCRIPT_DIR/$apk" ] || fail "Bundle is incomplete: missing $apk beside nuvio-phone-benchmark.sh."
    cp -f "$SCRIPT_DIR/$apk" "$STAGING_DIR/$apk" || fail "Could not stage $apk in $STAGING_DIR."
  done
  cp -f "$SCRIPT_DIR/nuvio-phone-benchmark.sh" "$STAGING_DIR/nuvio-phone-benchmark.sh" || fail "Could not stage the benchmark runner."
  exec sh "$STAGING_DIR/nuvio-phone-benchmark.sh" "$@"
fi

usage() {
  cat <<'HELP'
Nuvio Android phone-only startup benchmark

Keep all eight arm64-v8a APKs and this script together:
  Nuvio-full-BenchmarkRelease.apk
  Nuvio-full-BenchmarkRelease-Tests.apk
  Nuvio-playstore-BenchmarkRelease.apk
  Nuvio-playstore-BenchmarkRelease-Tests.apk
  Nuvio-full-NonMinifiedRelease.apk
  Nuvio-full-NonMinifiedRelease-Tests.apk
  Nuvio-playstore-NonMinifiedRelease.apk
  Nuvio-playstore-NonMinifiedRelease-Tests.apk
  nuvio-phone-benchmark.sh

Run from LADB (no computer required):
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install-profile full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh run full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install-profile playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh run playstore

Results are copied to /sdcard/Download/NuvioStartupBenchmark.
The benchmark app uses com.nuvio.media.benchmark; production Nuvio is untouched.
HELP
}

check_device() {
  command -v pm >/dev/null 2>&1 || fail "Run this script from LADB or another Android ADB shell, not a regular terminal."
  api="$(getprop ro.build.version.sdk | tr -d '\r')"
  case "$api" in ''|*[!0-9]*) fail "Cannot determine Android API level." ;; esac
  [ "$api" -ge 33 ] || fail "Baseline Profile collection without root requires Android 13 / API 33 or later. This device reports API $api."
  abi="$(getprop ro.product.cpu.abi | tr -d '\r')"
  [ "$abi" = "arm64-v8a" ] || fail "This phone-only benchmark bundle contains arm64-v8a APKs. Device ABI is $abi; use an arm64 Android phone."
  qemu="$(getprop ro.kernel.qemu | tr -d '\r')"
  hardware="$(getprop ro.hardware | tr -d '\r')"
  [ "$qemu" != "1" ] || fail "A physical Android phone is required; emulators are not supported for this comparison."
  case "$hardware" in ranchu|goldfish|qemu) fail "A physical Android phone is required; emulators are not supported for this comparison." ;; esac
}

apk_paths() {
  case "$FLAVOR:$ACTION" in
    full:install-profile)
      APP_APK="$SCRIPT_DIR/Nuvio-full-NonMinifiedRelease.apk"
      TEST_APK="$SCRIPT_DIR/Nuvio-full-NonMinifiedRelease-Tests.apk"
      ;;
    playstore:install-profile)
      APP_APK="$SCRIPT_DIR/Nuvio-playstore-NonMinifiedRelease.apk"
      TEST_APK="$SCRIPT_DIR/Nuvio-playstore-NonMinifiedRelease-Tests.apk"
      ;;
    full:install|full:collect|full:measure)
      APP_APK="$SCRIPT_DIR/Nuvio-full-BenchmarkRelease.apk"
      TEST_APK="$SCRIPT_DIR/Nuvio-full-BenchmarkRelease-Tests.apk"
      ;;
    playstore:install|playstore:collect|playstore:measure)
      APP_APK="$SCRIPT_DIR/Nuvio-playstore-BenchmarkRelease.apk"
      TEST_APK="$SCRIPT_DIR/Nuvio-playstore-BenchmarkRelease-Tests.apk"
      ;;
    *) fail "Unsupported flavor/action combination: $FLAVOR/$ACTION." ;;
  esac
}

resolve_instrumentation() {
  # com.android.test with android.experimental.self-instrumenting=true is
  # intentionally instrumented against its own test APK package. The app under
  # test is launched separately by Macrobenchmark using APP_PACKAGE.
  INSTRUMENTATION="$(pm list instrumentation 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *"(target=$TEST_PACKAGE)"*)
        component="$(printf '%s\n' "$line" | sed 's/^instrumentation://; s/[[:space:]].*//')"
        case "$component" in
          "$TEST_PACKAGE/"*) printf '%s\n' "$component" ;;
        esac
        ;;
    esac
  done | head -n 1)"
  [ -n "$INSTRUMENTATION" ] || fail "The self-instrumenting test APK ($TEST_PACKAGE) is not installed. Install the matching app and -Tests APK pair for this flavor first."
}

new_run_dir() {
  stamp="$(date '+%Y%m%d-%H%M%S')"
  RUN_DIR="$OUTPUT_ROOT/$FLAVOR/$ACTION-$stamp"
  mkdir -p "$RUN_DIR"
  MARKER="$RUN_DIR/.started"
  : > "$MARKER"
  # Ensure the filesystem timestamp of the marker precedes any benchmark output.
  sleep 2
}

copy_recent_outputs() {
  patterns="$1"
  : > "$RUN_DIR/copied-files.txt"
  # The patterns are fixed by this script, not supplied by the caller.
  case "$patterns" in
    profile)
      find "$MEDIA_ROOT" -type f -newer "$MARKER" \( -name '*baseline-prof*.txt' -o -name '*startup-prof*.txt' \) -print > "$RUN_DIR/files-to-copy.txt" 2>/dev/null || true
      ;;
    benchmark)
      find "$MEDIA_ROOT" -type f -newer "$MARKER" \( -name '*benchmarkData.json' -o -name '*.trace' -o -name '*.perfetto-trace' \) -print > "$RUN_DIR/files-to-copy.txt" 2>/dev/null || true
      ;;
  esac
  while IFS= read -r source_file; do
    [ -f "$source_file" ] || continue
    destination="$RUN_DIR/$(basename "$source_file")"
    cp -p "$source_file" "$destination"
    printf '%s\n' "$source_file -> $destination" >> "$RUN_DIR/copied-files.txt"
  done < "$RUN_DIR/files-to-copy.txt"
  rm -f "$RUN_DIR/files-to-copy.txt" "$MARKER"
  [ -s "$RUN_DIR/copied-files.txt" ] || fail "The test completed without producing the expected files. Check $RUN_DIR/instrumentation.log and Android's benchmark output directory."
}

require_macrobenchmark_cases() {
  for test_name in startupNoCompilation startupWithBaselineProfile; do
    grep -Fq "INSTRUMENTATION_STATUS: test=$test_name" "$RUN_DIR/instrumentation.log" || {
      fail "Macrobenchmark did not execute required comparison case '$test_name'. See $RUN_DIR/instrumentation.log."
    }
  done
}

count_json_occurrences() {
  awk -v needle="$1" '
    {
      remaining = $0
      while ((position = index(remaining, needle)) > 0) {
        count++
        remaining = substr(remaining, position + length(needle))
      }
    }
    END { print count + 0 }
  ' "$2"
}

count_json_named_results() {
  awk -v expected="$1" '
    {
      content = content $0
    }
    END {
      remaining = content
      pattern = "\"name\"[[:space:]]*:[[:space:]]*\"" expected "\""
      while (match(remaining, pattern)) {
        count++
        remaining = substr(remaining, RSTART + RLENGTH)
      }
      print count + 0
    }
  ' "$2"
}

validate_macrobenchmark_statistics() {
  awk '
    function count_matches(source, pattern,    rest, count) {
      rest = source
      count = 0
      while (match(rest, pattern)) {
        count++
        rest = substr(rest, RSTART + RLENGTH)
      }
      return count
    }

    function metric_field(object, field,    needle, start, rest) {
      needle = "\"" field "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?/)) {
        return substr(rest, 1, RLENGTH)
      }
      return ""
    }

    function validate_metric_object(object, metric_name,    minimum, median, maximum, runs_text, start, rest, close_pos, samples, sample_count, i, value, sample_min, sample_max, run_key_count) {
      minimum = metric_field(object, "minimum")
      median = metric_field(object, "median")
      maximum = metric_field(object, "maximum")

      if (minimum == "" || median == "" || maximum == "") {
        print "FAIL: " metric_name " is missing a numeric minimum, median, or maximum."
        return 0
      }
      if (minimum + 0 <= 0 || median + 0 <= 0 || maximum + 0 <= 0 || minimum + 0 > median + 0 || median + 0 > maximum + 0) {
        print "FAIL: " metric_name " has invalid statistics; require 0 < minimum <= median <= maximum."
        return 0
      }

      run_key_count = count_matches(object, "\"runs\"[[:space:]]*:")
      if (run_key_count != 1) {
        print "FAIL: " metric_name " must contain exactly one per-iteration runs array."
        return 0
      }
      start = index(object, "\"runs\"")
      rest = substr(object, start + length("\"runs\""))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (substr(rest, 1, 1) != "[") {
        print "FAIL: " metric_name " runs value is not an array."
        return 0
      }
      close_pos = index(rest, "]")
      if (close_pos == 0) {
        print "FAIL: " metric_name " runs array is incomplete."
        return 0
      }
      runs_text = substr(rest, 2, close_pos - 2)
      gsub(/[[:space:]]/, "", runs_text)
      if (runs_text == "") sample_count = 0
      else sample_count = split(runs_text, samples, ",")

      if (sample_count != expected_iterations) {
        print "FAIL: " metric_name " has " sample_count " samples; expected " expected_iterations "."
        return 0
      }

      sample_min = 0
      sample_max = 0
      for (i = 1; i <= sample_count; i++) {
        value = samples[i]
        if (value !~ /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ || value + 0 <= 0) {
          print "FAIL: " metric_name " contains a non-numeric or non-positive sample."
          return 0
        }
        if (i == 1 || value + 0 < sample_min) sample_min = value + 0
        if (i == 1 || value + 0 > sample_max) sample_max = value + 0
        if (value + 0 < minimum + 0 || value + 0 > maximum + 0) {
          print "FAIL: " metric_name " sample falls outside its reported minimum/maximum."
          return 0
        }
      }
      if (sample_min != minimum + 0 || sample_max != maximum + 0) {
        print "FAIL: " metric_name " reported minimum/maximum do not match the sample extrema."
        return 0
      }
      return 1
    }

    { json = json $0 "\n" }

    END {
      expected_iterations = 10
      count_no = count_matches(json, "\"name\"[[:space:]]*:[[:space:]]*\"startupNoCompilation\"")
      count_profile = count_matches(json, "\"name\"[[:space:]]*:[[:space:]]*\"startupWithBaselineProfile\"")
      count_class = count_matches(json, "\"className\"[[:space:]]*:[[:space:]]*\"com[.]nuvio[.]android[.]baselineprofile[.]StartupBenchmark\"")
      if (count_no != 1 || count_profile != 1 || count_class != 2) {
        print "FAIL: JSON must contain exactly one result for each startup case and two StartupBenchmark class records."
        exit 1
      }

      repeat_rest = json
      repeat_count = 0
      repeat_pattern = "\"repeatIterations\"[[:space:]]*:[[:space:]]*[0-9]+"
      while (match(repeat_rest, repeat_pattern)) {
        repeat_start = RSTART
        repeat_length = RLENGTH
        repeat_value = substr(repeat_rest, repeat_start, repeat_length)
        sub(/^.*:[[:space:]]*/, "", repeat_value)
        if (repeat_value + 0 != expected_iterations) {
          print "FAIL: each benchmark result must report repeatIterations=" expected_iterations "."
          exit 1
        }
        repeat_count++
        repeat_rest = substr(repeat_rest, repeat_start + repeat_length)
      }
      if (repeat_count != 2) {
        print "FAIL: JSON must contain repeatIterations for both startup results."
        exit 1
      }

      total_metric_objects = 0
      for (metric_name_index = 1; metric_name_index <= 2; metric_name_index++) {
        if (metric_name_index == 1) metric_name = "timeToInitialDisplayMs"
        else metric_name = "timeToFullDisplayMs"
        metric_pattern = "\"" metric_name "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
        metric_rest = json
        metric_count = 0
        while (match(metric_rest, metric_pattern)) {
          metric_start = RSTART
          metric_length = RLENGTH
          metric_object = substr(metric_rest, metric_start, metric_length)
          if (!validate_metric_object(metric_object, metric_name)) exit 1
          metric_count++
          total_metric_objects++
          metric_rest = substr(metric_rest, metric_start + metric_length)
        }
        if (metric_count != 2) {
          print "FAIL: JSON must contain exactly two " metric_name " results, one per startup case."
          exit 1
        }
      }
      if (total_metric_objects != 4) {
        print "FAIL: JSON must contain exactly four startup metric objects."
        exit 1
      }
      print "PASS: both startup results contain ten numeric TTID/TTFD samples and valid min/median/max statistics."
    }
  ' "$1"
}

require_macrobenchmark_result_data() {
  json_files=0
  no_compilation_results=0
  baseline_profile_results=0
  ttid_metrics=0
  ttfd_metrics=0

  for result_file in "$RUN_DIR"/*benchmarkData.json; do
    [ -f "$result_file" ] || continue
    json_files=$((json_files + 1))
    [ -s "$result_file" ] || fail "Macrobenchmark result file is empty: $result_file."

    no_compilation_results=$((no_compilation_results + $(count_json_named_results startupNoCompilation "$result_file")))
    baseline_profile_results=$((baseline_profile_results + $(count_json_named_results startupWithBaselineProfile "$result_file")))
    ttid_metrics=$((ttid_metrics + $(count_json_occurrences '"timeToInitialDisplayMs"' "$result_file")))
    ttfd_metrics=$((ttfd_metrics + $(count_json_occurrences '"timeToFullDisplayMs"' "$result_file")))
    validate_macrobenchmark_statistics "$result_file" || fail "Macrobenchmark JSON contains incomplete or invalid per-iteration startup statistics: $result_file."
  done

  [ "$json_files" -gt 0 ] || fail "Macrobenchmark did not produce a benchmarkData.json result file."
  [ "$no_compilation_results" -ge 1 ] && [ "$baseline_profile_results" -ge 1 ] || {
    fail "Macrobenchmark JSON must contain both startupNoCompilation and startupWithBaselineProfile results (found $no_compilation_results and $baseline_profile_results)."
  }
  [ "$ttid_metrics" -ge 2 ] && [ "$ttfd_metrics" -ge 2 ] || {
    fail "Macrobenchmark JSON must contain TTID and TTFD metrics for both startup results (found $ttid_metrics and $ttfd_metrics)."
  }
}

install_variant() {
  apk_paths
  [ -f "$APP_APK" ] || fail "Missing app APK: $APP_APK. Extract all eight APKs and this script into one folder."
  [ -f "$TEST_APK" ] || fail "Missing test APK: $TEST_APK. Extract all eight APKs and this script into one folder."
  if [ "$ACTION" = install-profile ]; then
    variant_label=nonMinifiedRelease
  else
    variant_label=benchmarkRelease
  fi
  printf 'Installing %s %s app and matching test APK...\n' "$FLAVOR" "$variant_label"
  install_status=0
  install_output="$(pm install -r "$APP_APK" 2>&1)" || install_status=$?
  if [ "$install_status" -eq 0 ]; then
    printf '%s\n' "$install_output"
  else
    printf '%s\n' "$install_output" >&2
    case "$install_output" in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        printf 'Replacing an older benchmark-only signing key. Official Nuvio is not touched.\n'
        pm uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
        pm uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
        pm install "$APP_APK" || fail "Could not reinstall the isolated benchmark app after removing its older signature."
        ;;
      *)
        fail "App installation failed. Your official Nuvio app was not changed."
        ;;
    esac
  fi
  test_install_status=0
  test_output="$(pm install -t -r "$TEST_APK" 2>&1)" || test_install_status=$?
  if [ "$test_install_status" -eq 0 ]; then
    printf '%s\n' "$test_output"
  else
    printf '%s\n' "$test_output" >&2
    case "$test_output" in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        pm uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
        pm install -t "$TEST_APK" || fail "Could not reinstall the isolated instrumentation test APK."
        ;;
      *)
        fail "Instrumentation APK installation failed. Your official Nuvio app was not changed."
        ;;
    esac
  fi
  resolve_instrumentation
  monkey -p "$APP_PACKAGE" 1 >/dev/null 2>&1 || fail "Could not launch the benchmark app."
  mkdir -p "$OUTPUT_ROOT"
  printf '%s:%s\n' "$ACTION" "$FLAVOR" > "$OUTPUT_ROOT/current-install.txt"
  printf '\nInstalled %s (%s). Open Nuvio, sign in, finish onboarding if needed, and leave it on Home.\n' "$FLAVOR" "$ACTION"
  printf 'Do not clear app data between full and playstore; both flavors share the isolated .benchmark app ID and signing key.\n'
  printf 'Production app com.nuvio.media and its data are separate and were not changed.\n'
}

require_installed_variant() {
  expected="$1:$FLAVOR"
  [ -f "$OUTPUT_ROOT/current-install.txt" ] || fail "No benchmark variant state was recorded. Run the matching install action first."
  installed="$(cat "$OUTPUT_ROOT/current-install.txt")"
  [ "$installed" = "$expected" ] || fail "Currently installed variant is '$installed', but '$expected' is required. Run the matching install action first."
}

run_instrumentation() {
  rule="$1"
  class_name="$2"
  case "$rule" in
    BaselineProfile) require_installed_variant install-profile ;;
    macrobenchmark) require_installed_variant install ;;
  esac
  resolve_instrumentation
  new_run_dir
  printf 'Running %s on %s. Keep the phone cool, plugged in, and otherwise idle.\n' "$rule" "$FLAVOR"
  set +e
  am instrument -w -r \
    -e androidx.benchmark.enabledRules "$rule" \
    -e class "$class_name" \
    "$INSTRUMENTATION" > "$RUN_DIR/instrumentation.log" 2>&1
  result=$?
  set -e
  cat "$RUN_DIR/instrumentation.log"
  [ "$result" -eq 0 ] || fail "Instrumentation returned exit code $result. See $RUN_DIR/instrumentation.log."
  if grep -Eiq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]|INSTRUMENTATION_CODE: -[0-9]' "$RUN_DIR/instrumentation.log"; then
    fail "The test reported a failure. See $RUN_DIR/instrumentation.log."
  fi
  case "$rule" in
    BaselineProfile)
      copy_recent_outputs profile
      ;;
    macrobenchmark)
      require_macrobenchmark_cases
      copy_recent_outputs benchmark
      require_macrobenchmark_result_data
      ;;
  esac
  printf '\nSaved results to: %s\n' "$RUN_DIR"
  printf 'Copied files:\n'
  cat "$RUN_DIR/copied-files.txt"
}

[ "$#" -ge 1 ] || { usage; exit 2; }
ACTION="$1"
if [ "$ACTION" = help ]; then usage; exit 0; fi
[ "$#" -eq 2 ] || { usage; exit 2; }
FLAVOR="$2"
case "$FLAVOR" in full|playstore) ;; *) fail "Flavor must be full or playstore." ;; esac
check_device

case "$ACTION" in
  install|install-profile)
    install_variant
    ;;
  collect)
    run_instrumentation BaselineProfile "$GENERATOR_CLASS"
    printf '\nProfile collection is complete. These text rules are candidates; they are not installed into the APK by this step. A new authorized build is required before measuring a newly collected profile.\n'
    ;;
  measure)
    run_instrumentation macrobenchmark "$BENCHMARK_CLASS"
    printf '\nThe JSON contains paired cold-start results for CompilationMode.None and CompilationMode.Partial(BaselineProfileMode.Require). Check both TTID and TTFD medians; the profile-enabled arm uses the profile already packaged in this benchmarkRelease APK, not the profile collected in the previous step.\n'
    ;;
  run)
    require_installed_variant install-profile
    run_instrumentation BaselineProfile "$GENERATOR_CLASS"
    ACTION=install
    install_variant
    run_instrumentation macrobenchmark "$BENCHMARK_CLASS"
    printf '\nFull profile collection and startup measurement completed for %s.\n' "$FLAVOR"
    ;;
  *) usage; fail "Unknown action '$ACTION'. Use install-profile, run, collect, install, measure or help." ;;
esac
