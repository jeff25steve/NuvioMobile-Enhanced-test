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
# AndroidX 1.5.0 uses InstrumentationRegistry.targetContext.externalMediaDirs.
# This self-instrumenting test module owns the output directory, not the app under test.
TEST_MEDIA_ROOT="${MEDIA_ROOT%/}/$TEST_PACKAGE"

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
      find "$TEST_MEDIA_ROOT" -type f -newer "$MARKER" \( -name '*baseline-prof*.txt' -o -name '*startup-prof*.txt' \) -print > "$RUN_DIR/files-to-copy.txt" 2>/dev/null || true
      ;;
    benchmark)
      find "$TEST_MEDIA_ROOT" -type f -newer "$MARKER" \( -name '*benchmarkData.json' -o -name '*.trace' -o -name '*.perfetto-trace' \) -print > "$RUN_DIR/files-to-copy.txt" 2>/dev/null || true
      ;;
  esac
  while IFS= read -r source_file; do
    [ -f "$source_file" ] || continue
    destination="$RUN_DIR/$(basename "$source_file")"
    cp -p "$source_file" "$destination"
    printf '%s\n' "$source_file -> $destination" >> "$RUN_DIR/copied-files.txt"
  done < "$RUN_DIR/files-to-copy.txt"
  rm -f "$RUN_DIR/files-to-copy.txt" "$MARKER"
  # A missing profile is validated by require_non_empty_startup_profile so the
  # user gets a specific Startup Profile diagnostic instead of this generic
  # file-copy error. Macrobenchmark must still produce result/trace artifacts.
  if [ "$patterns" != profile ] && [ ! -s "$RUN_DIR/copied-files.txt" ]; then
    fail "The test completed without producing the expected files. Check $RUN_DIR/instrumentation.log and Android's benchmark output directory."
  fi
}

require_non_empty_startup_profile() {
  startup_profile_count=0
  for profile_file in "$RUN_DIR"/*startup-prof*.txt; do
    [ -f "$profile_file" ] || continue
    startup_profile_count=$((startup_profile_count + 1))
    [ -s "$profile_file" ] || fail "Generated startup profile contains no rules: $profile_file."
    grep -Eq '[^[:space:]]' "$profile_file" || {
      fail "Generated startup profile contains no rules: $profile_file."
    }
  done
  [ "$startup_profile_count" -gt 0 ] || fail "No startup-prof text profile was produced. Check the Startup Profile collection and $RUN_DIR/instrumentation.log."
}

require_macrobenchmark_cases() {
  for test_name in 'startup[01-none-a]' 'startup[02-profile-b]' 'startup[03-profile-b]' 'startup[04-none-a]' 'startup[05-none-a]' 'startup[06-profile-b]' 'startup[07-profile-b]' 'startup[08-none-a]'; do
    grep -Fq "INSTRUMENTATION_STATUS: test=$test_name" "$RUN_DIR/instrumentation.log" || {
      fail "Macrobenchmark did not execute required ABBA block '$test_name'. See $RUN_DIR/instrumentation.log."
    }
  done
}

count_json_occurrences() {count_json_occurrences() {
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
      escaped = expected
      gsub(/\[/, "\\[", escaped)
      gsub(/\]/, "\\]", escaped)
      pattern = "\"name\"[[:space:]]*:[[:space:]]*\"" escaped "\""
      remaining = content
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
      if (match(rest, /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?/)) return substr(rest, 1, RLENGTH)
      return ""
    }

    function validate_metric_object(object, metric_name,    minimum, median, maximum, cv, start, rest, close_pos, runs_text, samples, n, i, value, sample_min, sample_max, sorted, j, min_index, swap, position, lower, upper, ratio, expected_median, difference, tolerance, mean, squared, expected_cv) {
      minimum = metric_field(object, "minimum")
      median = metric_field(object, "median")
      maximum = metric_field(object, "maximum")
      cv = metric_field(object, "coefficientOfVariation")
      if (minimum == "" || median == "" || maximum == "" || cv == "") {
        print "FAIL: " metric_name " is missing numeric min/median/max/CV statistics."
        return 0
      }
      if (minimum + 0 <= 0 || median + 0 <= 0 || maximum + 0 <= 0 || minimum + 0 > median + 0 || median + 0 > maximum + 0 || cv + 0 < 0) {
        print "FAIL: " metric_name " has invalid min/median/max/CV statistics."
        return 0
      }

      start = index(object, "\"runs\"")
      if (start == 0) {
        print "FAIL: " metric_name " has no raw runs array."
        return 0
      }
      rest = substr(object, start + length("\"runs\""))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (substr(rest, 1, 1) != "[") return 0
      close_pos = index(rest, "]")
      if (close_pos == 0) return 0
      runs_text = substr(rest, 2, close_pos - 2)
      gsub(/[[:space:]]/, "", runs_text)
      n = split(runs_text, samples, ",")
      if (n != expected_iterations) {
        print "FAIL: " metric_name " has " n " samples; expected " expected_iterations " per ABBA block."
        return 0
      }

      for (i = 1; i <= n; i++) {
        value = samples[i]
        if (value !~ /^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ || value + 0 <= 0) {
          print "FAIL: " metric_name " contains a non-numeric or non-positive sample."
          return 0
        }
        if (i == 1 || value + 0 < sample_min) sample_min = value + 0
        if (i == 1 || value + 0 > sample_max) sample_max = value + 0
        sorted[i] = value + 0
      }
      if (sample_min != minimum + 0 || sample_max != maximum + 0) {
        print "FAIL: " metric_name " reported min/max do not match raw block samples."
        return 0
      }

      for (i = 1; i < n; i++) {
        min_index = i
        for (j = i + 1; j <= n; j++) if (sorted[j] < sorted[min_index]) min_index = j
        if (min_index != i) {
          swap = sorted[i]
          sorted[i] = sorted[min_index]
          sorted[min_index] = swap
        }
      }
      position = 0.5 * (n - 1)
      lower = int(position) + 1
      upper = lower + 1
      if (upper > n) upper = n
      ratio = position - int(position)
      expected_median = sorted[lower] * (1 - ratio) + sorted[upper] * ratio
      difference = median + 0 - expected_median
      if (difference < 0) difference = -difference
      tolerance = 0.000001 * (1 + expected_median)
      if (difference > tolerance) {
        print "FAIL: " metric_name " median does not match the raw block samples."
        return 0
      }

      mean = 0
      for (i = 1; i <= n; i++) mean += samples[i] + 0
      mean /= n
      squared = 0
      for (i = 1; i <= n; i++) squared += (samples[i] + 0 - mean) ^ 2
      expected_cv = sqrt(squared / (n - 1)) / mean
      difference = cv + 0 - expected_cv
      if (difference < 0) difference = -difference
      tolerance = 0.000001 * (1 + expected_cv)
      if (difference > tolerance) {
        print "FAIL: " metric_name " coefficientOfVariation disagrees with raw block samples."
        return 0
      }
      return 1
    }

    { json = json $0 "\n" }

    END {
      expected_iterations = 3
      expected_blocks = 8
      test_names[1] = "startup[01-none-a]"
      test_names[2] = "startup[02-profile-b]"
      test_names[3] = "startup[03-profile-b]"
      test_names[4] = "startup[04-none-a]"
      test_names[5] = "startup[05-none-a]"
      test_names[6] = "startup[06-profile-b]"
      test_names[7] = "startup[07-profile-b]"
      test_names[8] = "startup[08-none-a]"

      class_count = count_matches(json, "\"className\"[[:space:]]*:[[:space:]]*\"com[.]nuvio[.]android[.]baselineprofile[.]StartupBenchmark\"")
      if (class_count != expected_blocks) {
        print "FAIL: JSON must contain exactly eight parameterized StartupBenchmark blocks."
        exit 1
      }
      for (i = 1; i <= expected_blocks; i++) {
        escaped = test_names[i]
        gsub(/\[/, "\\[", escaped)
        gsub(/\]/, "\\]", escaped)
        pattern = "\"name\"[[:space:]]*:[[:space:]]*\"" escaped "\""
        if (count_matches(json, pattern) != 1) {
          print "FAIL: JSON must contain exactly one result for " test_names[i] "."
          exit 1
        }
      }

      repeat_rest = json
      repeat_count = 0
      repeat_pattern = "\"repeatIterations\"[[:space:]]*:[[:space:]]*[0-9]+"
      while (match(repeat_rest, repeat_pattern)) {
        repeat_value = substr(repeat_rest, RSTART, RLENGTH)
        sub(/^.*:[[:space:]]*/, "", repeat_value)
        if (repeat_value + 0 != expected_iterations) {
          print "FAIL: each ABBA block must report repeatIterations=3."
          exit 1
        }
        repeat_count++
        repeat_rest = substr(repeat_rest, RSTART + RLENGTH)
      }
      if (repeat_count != expected_blocks) {
        print "FAIL: JSON must contain repeatIterations for all eight ABBA blocks."
        exit 1
      }

      for (metric_index = 1; metric_index <= 2; metric_index++) {
        metric_name = metric_index == 1 ? "timeToInitialDisplayMs" : "timeToFullDisplayMs"
        pattern = "\"" metric_name "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
        remaining = json
        metric_count = 0
        while (match(remaining, pattern)) {
          object = substr(remaining, RSTART, RLENGTH)
          if (!validate_metric_object(object, metric_name)) exit 1
          metric_count++
          remaining = substr(remaining, RSTART + RLENGTH)
        }
        if (metric_count != expected_blocks) {
          print "FAIL: JSON must contain exactly eight " metric_name " metric objects."
          exit 1
        }
      }

      remaining = json
      trace_count = 0
      trace_pattern = "\"profilerOutputs\"[[:space:]]*:[[:space:]]*\\[[^]]*\\]"
      while (match(remaining, trace_pattern)) {
        trace_array = substr(remaining, RSTART, RLENGTH)
        trace_types = count_matches(trace_array, "\"type\"[[:space:]]*:[[:space:]]*\"PerfettoTrace\"")
        trace_names = count_matches(trace_array, "\"filename\"[[:space:]]*:[[:space:]]*\"[^\"]+\"")
        if (trace_types != expected_iterations || trace_names != expected_iterations) {
          print "FAIL: each ABBA block must reference exactly three per-iteration Perfetto traces."
          exit 1
        }
        for (iteration = 0; iteration < expected_iterations; iteration++) {
          label_pattern = "\"label\"[[:space:]]*:[[:space:]]*\"Trace Iteration " iteration "\""
          if (count_matches(trace_array, label_pattern) != 1) {
            print "FAIL: each ABBA block must contain one matching trace label per sample."
            exit 1
          }
        }
        trace_count++
        remaining = substr(remaining, RSTART + RLENGTH)
      }
      if (trace_count != expected_blocks) {
        print "FAIL: JSON must contain profilerOutputs for all eight ABBA blocks."
        exit 1
      }
      print "PASS: eight ABBA blocks contain three numeric TTID/TTFD samples each, validated statistics and three Perfetto traces per block."
    }
  ' "$1"
}

extract_macrobenchmark_trace_filenames() {extract_macrobenchmark_trace_filenames() {
  awk '
    { json = json $0 "\n" }
    END {
      remaining = json
      array_pattern = "\"profilerOutputs\"[[:space:]]*:[[:space:]]*\\[[^]]*\\]"
      while (match(remaining, array_pattern)) {
        array_start = RSTART
        array_length = RLENGTH
        trace_array = substr(remaining, array_start, array_length)
        fields = trace_array
        filename_pattern = "\"filename\"[[:space:]]*:[[:space:]]*\"[^\"]+\""
        while (match(fields, filename_pattern)) {
          field_start = RSTART
          field_length = RLENGTH
          filename_field = substr(fields, field_start, field_length)
          sub(/^.*:[[:space:]]*"/, "", filename_field)
          sub(/"$/, "", filename_field)
          print filename_field
          fields = substr(fields, field_start + field_length)
        }
        remaining = substr(remaining, array_start + array_length)
      }
    }
  ' "$1"
}

validate_macrobenchmark_trace_files() {
  result_file="$1"
  trace_reference_file="$RUN_DIR/trace-references.txt"
  extract_macrobenchmark_trace_filenames "$result_file" > "$trace_reference_file"
  reference_count="$(awk 'END { print NR + 0 }' "$trace_reference_file")"
  [ "$reference_count" -eq 24 ] || fail "Macrobenchmark trace output must reference twenty-four per-iteration Perfetto files (three for each of eight ABBA blocks); found $reference_count references."

  unique_reference_count="$(sort -u "$trace_reference_file" | wc -l | tr -d ' ')"
  [ "$unique_reference_count" -eq 24 ] || fail "Macrobenchmark trace output contains duplicate filenames; expected twenty-four unique Perfetto traces, found $unique_reference_count."

  while IFS= read -r trace_filename; do
    [ -n "$trace_filename" ] || fail "Macrobenchmark trace output contains an empty filename reference."
    trace_basename="$(basename "$trace_filename")"
    [ "$trace_basename" = "$trace_filename" ] || fail "Macrobenchmark trace output contains a non-local filename reference: $trace_filename."
    [ -s "$RUN_DIR/$trace_filename" ] || fail "Macrobenchmark trace output referenced by JSON is missing or empty: $trace_filename."
  done < "$trace_reference_file"

  trace_file_count=0
  for trace_file in "$RUN_DIR"/*.trace "$RUN_DIR"/*.perfetto-trace; do
    [ -f "$trace_file" ] || continue
    [ -s "$trace_file" ] || fail "Macrobenchmark trace output is empty: $trace_file."
    trace_file_count=$((trace_file_count + 1))
  done
  [ "$trace_file_count" -eq 24 ] || fail "Macrobenchmark trace output must contain exactly twenty-four non-empty per-iteration Perfetto traces; found $trace_file_count."
}

write_macrobenchmark_summary() {
  result_file="$1"
  summary_file="$RUN_DIR/benchmark-summary.txt"
  awk '
    function field(object, name,    needle, start, rest) {
      needle = "\"" name "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?/)) return substr(rest, 1, RLENGTH)
      return ""
    }
    function string_field(object, name,    needle, start, rest) {
      needle = "\"" name "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^"[^"]*"/)) return substr(rest, 2, RLENGTH - 2)
      return ""
    }
    function runs(object, values,    start, rest, close_pos, text) {
      start = index(object, "\"runs\"")
      if (!start) return 0
      rest = substr(object, start + length("\"runs\""))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (substr(rest, 1, 1) != "[") return 0
      close_pos = index(rest, "]")
      if (!close_pos) return 0
      text = substr(rest, 2, close_pos - 2)
      gsub(/[[:space:]]/, "", text)
      return split(text, values, ",")
    }
    function trace_name(object, iteration,    pattern, array, trace) {
      pattern = "\"profilerOutputs\"[[:space:]]*:[[:space:]]*\\[[^]]*\\]"
      if (!match(object, pattern)) return ""
      array = substr(object, RSTART, RLENGTH)
      pattern = "\\{[^}]*\"label\"[[:space:]]*:[[:space:]]*\"Trace Iteration " iteration "\"[^}]*\\}"
      if (!match(array, pattern)) return ""
      trace = substr(array, RSTART, RLENGTH)
      return string_field(trace, "filename")
    }
    function median(values, n,    sorted, i, j, x) {
      for (i = 1; i <= n; i++) sorted[i] = values[i]
      for (i = 2; i <= n; i++) {
        x = sorted[i]
        j = i - 1
        while (j >= 1 && sorted[j] > x) {
          sorted[j + 1] = sorted[j]
          j--
        }
        sorted[j + 1] = x
      }
      if (n % 2) return sorted[(n + 1) / 2]
      return (sorted[n / 2] + sorted[n / 2 + 1]) / 2
    }
    function min_value(values, n,    i, result) {
      result = values[1]
      for (i = 2; i <= n; i++) if (values[i] < result) result = values[i]
      return result
    }
    function max_value(values, n,    i, result) {
      result = values[1]
      for (i = 2; i <= n; i++) if (values[i] > result) result = values[i]
      return result
    }
    function cv(values, n,    i, mean, squared, difference) {
      mean = 0
      for (i = 1; i <= n; i++) mean += values[i]
      mean /= n
      squared = 0
      for (i = 1; i <= n; i++) {
        difference = values[i] - mean
        squared += difference * difference
      }
      return sqrt(squared / (n - 1)) / mean
    }
    { json = json $0 "\n" }
    END {
      names[1] = "startup[01-none-a]"
      names[2] = "startup[02-profile-b]"
      names[3] = "startup[03-profile-b]"
      names[4] = "startup[04-none-a]"
      names[5] = "startup[05-none-a]"
      names[6] = "startup[06-profile-b]"
      names[7] = "startup[07-profile-b]"
      names[8] = "startup[08-none-a]"
      metric_names[1] = "timeToInitialDisplayMs"
      metric_names[2] = "timeToFullDisplayMs"
      for (b = 1; b <= 8; b++) {
        escaped = names[b]
        gsub(/\[/, "\\[", escaped)
        gsub(/\]/, "\\]", escaped)
        pattern = "\"name\"[[:space:]]*:[[:space:]]*\"" escaped "\""
        if (!match(json, pattern)) {
          print "FAIL: missing " names[b] " while writing ABBA summary."
          exit 1
        }
        rest = substr(json, RSTART + RLENGTH)
        next_pattern = "\"name\"[[:space:]]*:[[:space:]]*\"[^\"]+\""
        if (match(rest, next_pattern)) object = substr(rest, 1, RSTART - 1)
        else object = rest
        objects[b] = object
        is_none = (b == 1 || b == 4 || b == 5 || b == 8)
        for (m = 1; m <= 2; m++) {
          metric_pattern = "\"" metric_names[m] "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          if (!match(object, metric_pattern)) {
            print "FAIL: missing " metric_names[m] " for " names[b] "."
            exit 1
          }
          metric_object = substr(object, RSTART, RLENGTH)
          n = runs(metric_object, sample)
          if (n != 3) {
            print "FAIL: " metric_names[m] " for " names[b] " must contain three samples."
            exit 1
          }
          for (i = 1; i <= n; i++) {
            if (m == 1) block_ttid[b, i] = sample[i] + 0
            else block_ttfd[b, i] = sample[i] + 0
            if (is_none) {
              if (m == 1) none_ttid[++none_ttid_n] = sample[i] + 0
              else none_ttfd[++none_ttfd_n] = sample[i] + 0
            } else {
              if (m == 1) profile_ttid[++profile_ttid_n] = sample[i] + 0
              else profile_ttfd[++profile_ttfd_n] = sample[i] + 0
            }
            block_trace[b, i] = trace_name(object, i - 1)
            if (block_trace[b, i] == "") {
              print "FAIL: missing trace reference for " names[b] " iteration " (i - 1) "."
              exit 1
            }
          }
        }
      }
      if (none_ttid_n != 12 || profile_ttid_n != 12 || none_ttfd_n != 12 || profile_ttfd_n != 12) {
        print "FAIL: ABBA summary must pool twelve samples per comparison arm."
        exit 1
      }

      device_model = string_field(json, "model")
      api = field(json, "sdk")
      cores = field(json, "cpuCoreCount")
      max_freq = field(json, "cpuMaxFreqHz")
      locked = "not reported"
      if (match(json, "\"cpuLocked\"[[:space:]]*:[[:space:]]*(true|false)")) {
        locked = substr(json, RSTART, RLENGTH)
        sub(/^.*:[[:space:]]*/, "", locked)
      }
      memory = field(json, "memTotalBytes")
      art = field(json, "artMainlineVersion")
      sustained = "not reported"
      if (match(json, "\"sustainedPerformanceModeEnabled\"[[:space:]]*:[[:space:]]*(true|false)")) {
        sustained = substr(json, RSTART, RLENGTH)
        sub(/^.*:[[:space:]]*/, "", sustained)
      }
      codename = string_field(json, "osCodenameAbbreviated")
      if (device_model == "") device_model = "not reported"
      if (api == "") api = "not reported"
      if (cores == "") cores = "not reported"
      if (max_freq == "") max_freq = "not reported"
      if (memory == "") memory = "not reported"
      if (art == "") art = "not reported"
      if (codename == "") codename = "not reported"

      print "Nuvio Android cold-start benchmark"
      print "Benchmark device context"
      printf "  Device: %s | Android API: %s\n", device_model, api
      printf "  CPU: %s cores | max frequency %s Hz | clocks locked: %s\n", cores, max_freq, locked
      printf "  Memory: %s bytes | ART Mainline: %s | sustained performance mode: %s\n", memory, art, sustained
      printf "  Android release codename: %s\n", codename
      print "  Install path: APK sideload via pm install; not Google Play delivery."
      print "  The playstore build-flavor label does not mean this APK was installed from Google Play."
      if (api ~ /^[0-9]+$/ && api + 0 == 33) {
        print "  ART comparability: API 33 uses the pre-34 reset path; unrooted user builds reinstall for CompilationMode.None."
        print "  API 33 warning: do not treat this as a Play-equivalent isolated Baseline Profile comparison; ART verification state can differ."
      } else if (api ~ /^[0-9]+$/ && api + 0 >= 34) {
        print "  ART comparability: API 34+ CompilationMode.None uses verify and clears app ART profiles before measurement."
        print "  This controls the pre-34 verification-state confounder; the APK is still sideloaded, not Play-delivered."
      }
      print ""
      print "Comparison: no compilation vs required packaged Baseline Profile"
      print "Design: two ABBA rounds (A/B/B/A), with three cold starts per block."
      print "12 raw samples per comparison arm (24 cold starts total)"
      print "A = CompilationMode.None | B = Partial(BaselineProfileMode.Require)"
      print ""
      for (m = 1; m <= 2; m++) {
        metric = metric_names[m]
        if (m == 1) {
          a = median(none_ttid, 12); b = median(profile_ttid, 12)
          amin = min_value(none_ttid, 12); amax = max_value(none_ttid, 12)
          bmin = min_value(profile_ttid, 12); bmax = max_value(profile_ttid, 12)
          acv = cv(none_ttid, 12); bcv = cv(profile_ttid, 12)
        } else {
          a = median(none_ttfd, 12); b = median(profile_ttfd, 12)
          amin = min_value(none_ttfd, 12); amax = max_value(none_ttfd, 12)
          bmin = min_value(profile_ttfd, 12); bmax = max_value(profile_ttfd, 12)
          acv = cv(none_ttfd, 12); bcv = cv(profile_ttfd, 12)
        }
        delta = b - a
        percent = delta / a * 100
        print metric
        printf "  No compilation: pooled median %.1f ms (range %.1f..%.1f ms; CV %.2f%%; n=12)\n", a, amin, amax, acv * 100
        printf "  Baseline Profile: pooled median %.1f ms (range %.1f..%.1f ms; CV %.2f%%; n=12)\n", b, bmin, bmax, bcv * 100
        printf "  Median difference (profile - no compilation): %+.1f ms (%+.1f%%)\n\n", delta, percent
      }
      print "Per-block samples and matching Perfetto traces"
      print "The ABBA sequence is retained below; every measured sample links to its own trace."
      print ""
      for (block = 1; block <= 8; block++) {
        printf "%s (%s)\n", names[block], (block == 1 || block == 4 || block == 5 || block == 8) ? "A / no compilation" : "B / Baseline Profile"
        for (i = 1; i <= 3; i++) {
          printf "  Iteration %d: TTID %.1f ms | TTFD %.1f ms | trace %s\n", i - 1, block_ttid[block, i], block_ttfd[block, i], block_trace[block, i]
        }
        print ""
      }
      print "Interpretation: descriptive only; twelve samples per arm do not establish statistical significance."
      print "Inspect per-block traces and raw samples for skew, thermal drift, and compilation-state effects."
    }
  ' "$result_file" > "$summary_file" || fail "Could not create the human-readable ABBA Macrobenchmark summary."
  [ -s "$summary_file" ] || fail "Macrobenchmark summary is empty."
}

require_macrobenchmark_result_data() {
  json_files=0
  no_compilation_results=0
  baseline_profile_results=0
  ttid_metrics=0
  ttfd_metrics=0
  summary_result_file=""

  for result_file in "$RUN_DIR"/*benchmarkData.json; do
    [ -f "$result_file" ] || continue
    json_files=$((json_files + 1))
    [ -s "$result_file" ] || fail "Macrobenchmark result file is empty: $result_file."
    [ -n "$summary_result_file" ] || summary_result_file="$result_file"
    for block_name in 'startup[01-none-a]' 'startup[04-none-a]' 'startup[05-none-a]' 'startup[08-none-a]'; do
      no_compilation_results=$((no_compilation_results + $(count_json_named_results "$block_name" "$result_file")))
    done
    for block_name in 'startup[02-profile-b]' 'startup[03-profile-b]' 'startup[06-profile-b]' 'startup[07-profile-b]'; do
      baseline_profile_results=$((baseline_profile_results + $(count_json_named_results "$block_name" "$result_file")))
    done
    ttid_metrics=$((ttid_metrics + $(count_json_occurrences '"timeToInitialDisplayMs"' "$result_file")))
    ttfd_metrics=$((ttfd_metrics + $(count_json_occurrences '"timeToFullDisplayMs"' "$result_file")))
    validate_macrobenchmark_statistics "$result_file" || fail "Macrobenchmark JSON contains incomplete or invalid per-block startup statistics: $result_file."
    validate_macrobenchmark_trace_files "$result_file"
  done

  [ "$json_files" -gt 0 ] || fail "Macrobenchmark did not produce a benchmarkData.json result file."
  [ "$no_compilation_results" -eq 4 ] && [ "$baseline_profile_results" -eq 4 ] || {
    fail "Macrobenchmark JSON must contain all eight ABBA blocks (four A/no-compilation and four B/Baseline Profile; found $no_compilation_results and $baseline_profile_results)."
  }
  [ "$ttid_metrics" -eq 8 ] && [ "$ttfd_metrics" -eq 8 ] || {
    fail "Macrobenchmark JSON must contain eight TTID and eight TTFD metric objects (one of each per ABBA block; found $ttid_metrics and $ttfd_metrics)."
  }
  write_macrobenchmark_summary "$summary_result_file"
}

install_variant() {install_variant() {
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

capture_device_environment() {
  label="$1"
  snapshot="$RUN_DIR/environment-$label.txt"
  {
    printf 'Capture: %s\n' "$label"
    printf 'Timestamp: '
    date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'unavailable\n'
    printf 'Model: '
    getprop ro.product.model 2>/dev/null || printf 'unavailable\n'
    printf 'Android API: '
    getprop ro.build.version.sdk 2>/dev/null || printf 'unavailable\n'
    printf 'Build fingerprint: '
    getprop ro.build.fingerprint 2>/dev/null || printf 'unavailable\n'
    printf 'Boot completed: '
    getprop sys.boot_completed 2>/dev/null || printf 'unavailable\n'
    # Cold starts shortly after device boot can be slower because system services
    # and other applications are still competing for CPU and storage.
    printf 'Device uptime since boot: '
    if [ -r /proc/uptime ]; then
      awk '
        NR == 1 && $1 ~ /^[0-9]+([.][0-9]+)?$/ {
          printf "%.0f seconds\n", $1
          printed = 1
        }
        END {
          if (!printed) print "unavailable"
        }
      ' /proc/uptime
    else
      printf 'unavailable\n'
    fi

    printf '\nBattery state (raw Android dumpsys fields):\n'
    if command -v dumpsys >/dev/null 2>&1; then
      dumpsys battery 2>&1 | awk '/AC powered|USB powered|Wireless powered|status:|level:|temperature:|voltage:/ { print }' || true
      printf '\nThermal service (first 80 lines):\n'
      dumpsys thermalservice 2>&1 | sed -n '1,80p' || true
    else
      printf 'dumpsys unavailable\n'
    fi

    printf '\nBattery saver (settings global low_power): '
    settings get global low_power 2>&1 || printf 'unavailable\n'
    printf 'Brightness mode (0 manual, 1 automatic): '
    settings get system screen_brightness_mode 2>&1 || printf 'unavailable\n'
    printf 'Screen brightness setting: '
    settings get system screen_brightness 2>&1 || printf 'unavailable\n'
  } > "$snapshot" 2>&1 || {
    printf 'could not write environment snapshot: %s\n' "$snapshot" >&2
    return 0
  }
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
  if [ "$rule" = macrobenchmark ]; then
    capture_device_environment before
  fi
  set +e
  am instrument -w -r \
    -e androidx.benchmark.enabledRules "$rule" \
    -e class "$class_name" \
    "$INSTRUMENTATION" > "$RUN_DIR/instrumentation.log" 2>&1
  result=$?
  set -e
  if [ "$rule" = macrobenchmark ]; then
    capture_device_environment after
  fi
  cat "$RUN_DIR/instrumentation.log"
  [ "$result" -eq 0 ] || fail "Instrumentation returned exit code $result. See $RUN_DIR/instrumentation.log."
  if grep -Eiq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]' "$RUN_DIR/instrumentation.log"; then
    fail "The test reported a failure. See $RUN_DIR/instrumentation.log."
  fi
  case "$rule" in
    BaselineProfile)
      copy_recent_outputs profile
      require_non_empty_startup_profile
      ;;
    macrobenchmark)
      require_macrobenchmark_cases
      copy_recent_outputs benchmark
      require_macrobenchmark_result_data
      printf 'Generated benchmark summary -> %s\n' "$RUN_DIR/benchmark-summary.txt" >> "$RUN_DIR/copied-files.txt"
      cat "$RUN_DIR/benchmark-summary.txt"
      ;;
  esac
  printf '\nSaved results to: %s\n' "$RUN_DIR"
  if [ "$rule" = macrobenchmark ]; then
    printf 'Device condition snapshots: %s and %s\n' "$RUN_DIR/environment-before.txt" "$RUN_DIR/environment-after.txt"
  fi
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
    # The install marker is written above; label the following result directory as a measurement.
    ACTION=measure
    run_instrumentation macrobenchmark "$BENCHMARK_CLASS"
    printf '\nFull profile collection and startup measurement completed for %s.\n' "$FLAVOR"
    ;;
  *) usage; fail "Unknown action '$ACTION'. Use install-profile, run, collect, install, measure or help." ;;
esac
