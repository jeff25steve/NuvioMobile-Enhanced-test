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
  case "$ACTION" in
    run|measure)
      if [ "$api" -eq 33 ]; then
        # AndroidX Macrobenchmark may uninstall/reinstall the target on unrooted
        # pre-34 devices when resetting compilation, which erases signed-in Home.
        # Mirror AndroidX's instrumentation-shell root capability check exactly.
        root_id="$(id 2>/dev/null || true)"
        case "$root_id" in
          *"uid=0(root)"*) ;;
          *)
            root_id="$(su root id 2>/dev/null || true)"
            case "$root_id" in
              *"uid=0(root)"*) ;;
              *) fail "Authenticated Home measurement on API 33 requires a rooted instrumentation shell because AndroidX resets compilation by reinstalling the app on unrooted pre-34 devices, which clears sign-in data. Use API 34+ or make 'su root id' available; profile collection alone remains supported on API 33." ;;
            esac
            ;;
        esac
      fi
      ;;
  esac
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
  instrumentation_candidates="$(pm list instrumentation 2>/dev/null | awk -v package="$TEST_PACKAGE" '
    index($0, "(target=" package ")") {
      component = $1
      sub(/^instrumentation:/, "", component)
      if (index(component, package "/") == 1) print component
    }
  ')"
  instrumentation_count="$(printf '%s\n' "$instrumentation_candidates" | awk 'NF { count++ } END { print count + 0 }')"
  [ "$instrumentation_count" -eq 1 ] || fail "Expected exactly one self-instrumenting instrumentation component for '$TEST_PACKAGE'; found $instrumentation_count. Reinstall the matching benchmark test APK."
  INSTRUMENTATION="$instrumentation_candidates"
  expected_instrumentation="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
  [ "$INSTRUMENTATION" = "$expected_instrumentation" ] || fail "Unexpected instrumentation runner '$INSTRUMENTATION'; expected '$expected_instrumentation'. Reinstall the matching benchmark test APK."
}

file_sha256() {
  file="$1"
  [ -r "$file" ] || { printf 'unavailable\n'; return 0; }
  if command -v sha256sum >/dev/null 2>&1; then
    hash_output="$(sha256sum "$file" 2>/dev/null)" || hash_output=""
  elif command -v toybox >/dev/null 2>&1; then
    hash_output="$(toybox sha256sum "$file" 2>/dev/null)" || hash_output=""
  else
    hash_output=""
  fi
  hash_value="$(printf '%s\n' "$hash_output" | awk 'NF >= 1 && length($1) == 64 && $1 !~ /[^0-9a-fA-F]/ { print $1; exit }')"
  [ -n "$hash_value" ] && printf '%s\n' "$hash_value" || printf 'unavailable\n'
}

install_provenance_file() {
  printf '%s/current-install-provenance.txt\n' "$OUTPUT_ROOT"
}

load_install_provenance() {
  expected_saved_variant="$1"
  install_state="$(install_provenance_file)"
  [ -r "$install_state" ] || fail "No APK provenance was saved for the installed variant. Reinstall the matching benchmark APK pair."
  saved_variant="$(sed -n 's/^Variant: //p' "$install_state")"
  saved_app_name="$(sed -n 's/^App APK source filename: //p' "$install_state")"
  saved_app_hash="$(sed -n 's/^App APK source SHA-256: //p' "$install_state")"
  saved_test_name="$(sed -n 's/^Test APK source filename: //p' "$install_state")"
  saved_test_hash="$(sed -n 's/^Test APK source SHA-256: //p' "$install_state")"
  [ "$saved_variant" = "$expected_saved_variant" ] || fail "Saved APK provenance belongs to '$saved_variant', not '$expected_saved_variant'. Reinstall the matching APK pair."
  case "$expected_saved_variant" in
    install-profile:*)
      expected_app_name="Nuvio-$FLAVOR-NonMinifiedRelease.apk"
      expected_test_name="Nuvio-$FLAVOR-NonMinifiedRelease-Tests.apk"
      ;;
    install:*)
      expected_app_name="Nuvio-$FLAVOR-BenchmarkRelease.apk"
      expected_test_name="Nuvio-$FLAVOR-BenchmarkRelease-Tests.apk"
      ;;
    *) fail "Unsupported saved APK provenance variant '$expected_saved_variant'." ;;
  esac
  [ "$saved_app_name" = "$expected_app_name" ] && [ "$saved_test_name" = "$expected_test_name" ] || fail "Saved APK provenance filenames do not match the expected $expected_saved_variant APK pair."
  case "$saved_app_name:$saved_test_name" in
    *..*|*/*|:*|*:) fail "Saved APK provenance contains an invalid source filename." ;;
  esac
  case "$saved_app_hash:$saved_test_hash" in
    unavailable:*|*:unavailable|:*) fail "Saved APK provenance has an unavailable SHA-256. Reinstall from a bundle with working SHA-256 support." ;;
  esac
  [ "${#saved_app_hash}" -eq 64 ] && [ "${#saved_test_hash}" -eq 64 ] || fail "Saved APK provenance contains an invalid SHA-256 value."
  case "$saved_app_hash$saved_test_hash" in
    *[!0-9a-fA-F]*) fail "Saved APK provenance contains a non-hexadecimal SHA-256 value." ;;
  esac
}

verify_installed_package_apk() {
  package_name="$1"
  expected_hash="$2"
  package_label="$3"
  package_paths="$(pm path "$package_name" 2>/dev/null | awk '/^package:/ { path=substr($0,9); if (path != "") { count++; result=path } } END { if (count == 1) print result; else if (count > 1) print "MULTIPLE" }')"
  [ -n "$package_paths" ] || fail "Android did not return exactly one installed APK path for $package_label package '$package_name'. Reinstall the matching benchmark APK pair."
  [ "$package_paths" != MULTIPLE ] || fail "Installed $package_label package '$package_name' exposes multiple APK paths; the benchmark bundle expects one standalone APK."
  if [ ! -r "$package_paths" ]; then
    printf 'UNVERIFIED|%s|unavailable\n' "$package_paths"
    return 0
  fi
  installed_hash="$(file_sha256 "$package_paths")"
  if [ "$installed_hash" = unavailable ]; then
    printf 'UNVERIFIED|%s|unavailable\n' "$package_paths"
    return 0
  fi
  if [ "$installed_hash" != "$expected_hash" ]; then
    fail "Installed $package_label APK SHA-256 does not match the saved source APK. Reinstall the matching variant before measuring."
  fi
  printf 'MATCH|%s|%s\n' "$package_paths" "$installed_hash"
}

write_run_provenance() {
  app_installed_result="$(verify_installed_package_apk "$APP_PACKAGE" "$saved_app_hash" app)"
  test_installed_result="$(verify_installed_package_apk "$TEST_PACKAGE" "$saved_test_hash" test)"
  app_installed_verdict="${app_installed_result%%|*}"
  app_installed_rest="${app_installed_result#*|}"
  app_installed_path="${app_installed_rest%%|*}"
  app_installed_hash="${app_installed_rest#*|}"
  test_installed_verdict="${test_installed_result%%|*}"
  test_installed_rest="${test_installed_result#*|}"
  test_installed_path="${test_installed_rest%%|*}"
  test_installed_hash="${test_installed_rest#*|}"
  {
    printf 'Runner path: %s\n' "$SCRIPT_DIR/nuvio-phone-benchmark.sh"
    printf 'Runner SHA-256: %s\n' "$(file_sha256 "$SCRIPT_DIR/nuvio-phone-benchmark.sh")"
    printf 'Runner staged path: %s\n' "$STAGING_DIR/nuvio-phone-benchmark.sh"
    printf 'Staged runner SHA-256: %s\n' "$(file_sha256 "$STAGING_DIR/nuvio-phone-benchmark.sh")"
    printf 'Flavor: %s\nAction: %s\n' "$FLAVOR" "$ACTION"
    printf 'App package: %s\nTest package: %s\n' "$APP_PACKAGE" "$TEST_PACKAGE"
    printf 'App APK source filename: %s\nApp APK source SHA-256: %s\n' "$saved_app_name" "$saved_app_hash"
    printf 'Test APK source filename: %s\nTest APK source SHA-256: %s\n' "$saved_test_name" "$saved_test_hash"
    printf 'App installed APK path: %s\nApp installed APK SHA-256: %s\nApp installed APK verification: %s\n' "$app_installed_path" "$app_installed_hash" "$app_installed_verdict"
    printf 'Test installed APK path: %s\nTest installed APK SHA-256: %s\nTest installed APK verification: %s\n' "$test_installed_path" "$test_installed_hash" "$test_installed_verdict"
    printf 'Instrumentation component: %s\n' "$INSTRUMENTATION"
  } > "$RUN_DIR/provenance.txt"
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
  # Missing Startup and Home browse profile outputs are validated by their
  # dedicated checks so the user gets a specific journey diagnostic instead
  # of this generic file-copy error. Macrobenchmark must still produce results.
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

require_non_empty_home_browse_profile() {
  home_browse_profile_count=0
  for profile_file in "$RUN_DIR"/*home_browse-baseline-prof*.txt; do
    [ -f "$profile_file" ] || continue
    home_browse_profile_count=$((home_browse_profile_count + 1))
    [ -s "$profile_file" ] || fail "Generated Home browse Baseline Profile contains no rules: $profile_file."
    grep -Eq '[^[:space:]]' "$profile_file" || {
      fail "Generated Home browse Baseline Profile contains no rules: $profile_file."
    }
  done
  [ "$home_browse_profile_count" -gt 0 ] || fail "No home_browse Baseline Profile text file was produced. Ensure Home has a populated, horizontally scrollable catalogue and check $RUN_DIR/instrumentation.log."
}

require_macrobenchmark_cases() {
  for test_name in startupNoCompilation startupPartialWithoutBaselineProfile startupWithBaselineProfile; do
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

    function validate_metric_object(object, metric_name,    minimum, median, maximum, coefficient_variation, runs_text, start, rest, close_pos, samples, sample_count, i, value, sample_min, sample_max, run_key_count, sorted_samples, j, min_index, swap_value, median_position, lower_index, upper_index, interpolation_ratio, expected_median, median_difference, median_tolerance, sample_mean, squared_deviation_sum, expected_cv, cv_difference, cv_tolerance) {
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

      coefficient_variation = metric_field(object, "coefficientOfVariation")
      if (coefficient_variation == "") {
        print "FAIL: " metric_name " is missing its AndroidX coefficientOfVariation."
        return 0
      }
      if (coefficient_variation !~ /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ || coefficient_variation + 0 < 0) {
        print "FAIL: " metric_name " has an invalid coefficientOfVariation."
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

      # Match AndroidX MetricResult.getPercentile(sortedRuns, 50): the
      # 50th-percentile index is 0.5 * (n - 1), with linear interpolation.
      for (i = 1; i <= sample_count; i++) sorted_samples[i] = samples[i] + 0
      for (i = 1; i < sample_count; i++) {
        min_index = i
        for (j = i + 1; j <= sample_count; j++) {
          if (sorted_samples[j] < sorted_samples[min_index]) min_index = j
        }
        if (min_index != i) {
          swap_value = sorted_samples[i]
          sorted_samples[i] = sorted_samples[min_index]
          sorted_samples[min_index] = swap_value
        }
      }
      median_position = 0.5 * (sample_count - 1)
      lower_index = int(median_position) + 1
      upper_index = lower_index + 1
      if (upper_index > sample_count) upper_index = sample_count
      interpolation_ratio = median_position - int(median_position)
      expected_median = sorted_samples[lower_index] * (1 - interpolation_ratio) + sorted_samples[upper_index] * interpolation_ratio
      median_difference = median + 0 - expected_median
      if (median_difference < 0) median_difference = -median_difference
      median_tolerance = 0.000001 * (1 + expected_median)
      if (median_difference > median_tolerance) {
        print "FAIL: " metric_name " reported median does not match the 50th percentile of its per-iteration runs."
        return 0
      }

      # AndroidX MetricResult uses sample standard deviation (n - 1)
      # divided by arithmetic mean for coefficientOfVariation.
      sample_mean = 0
      for (i = 1; i <= sample_count; i++) sample_mean += samples[i] + 0
      sample_mean /= sample_count
      squared_deviation_sum = 0
      for (i = 1; i <= sample_count; i++) squared_deviation_sum += (samples[i] + 0 - sample_mean) ^ 2
      expected_cv = sqrt(squared_deviation_sum / (sample_count - 1)) / sample_mean
      cv_difference = coefficient_variation + 0 - expected_cv
      if (cv_difference < 0) cv_difference = -cv_difference
      cv_tolerance = 0.000001 * (1 + expected_cv)
      if (cv_difference > cv_tolerance) {
        print "FAIL: " metric_name " reported coefficientOfVariation does not match its per-iteration runs."
        return 0
      }
      return 1
    }


    function validate_art_metric_object(object, metric_name,    minimum, median, maximum, coefficient_variation, start, rest, close_pos, runs_text, samples, sample_count, i, value, sample_min, sample_max, sorted_samples, j, min_index, swap_value, median_position, lower_index, upper_index, interpolation_ratio, expected_median, median_difference, median_tolerance, sample_mean, squared_deviation_sum, expected_cv, cv_difference, cv_tolerance) {
      minimum = metric_field(object, "minimum")
      median = metric_field(object, "median")
      maximum = metric_field(object, "maximum")

      if (minimum == "" || median == "" || maximum == "") {
        print "FAIL: " metric_name " is missing a numeric minimum, median, or maximum."
        return 0
      }
      if (minimum + 0 < 0 || median + 0 < 0 || maximum + 0 < 0 || minimum + 0 > median + 0 || median + 0 > maximum + 0) {
        print "FAIL: " metric_name " has invalid statistics; require 0 <= minimum <= median <= maximum."
        return 0
      }

      coefficient_variation = metric_field(object, "coefficientOfVariation")
      if (coefficient_variation == "" || coefficient_variation !~ /^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/) {
        print "FAIL: " metric_name " is missing a valid non-negative AndroidX coefficientOfVariation."
        return 0
      }

      start = index(object, "\"runs\"")
      if (start == 0 || count_matches(object, "\"runs\"[[:space:]]*:") != 1) {
        print "FAIL: " metric_name " must contain exactly one per-iteration runs array."
        return 0
      }
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
      sample_mean = 0
      for (i = 1; i <= sample_count; i++) {
        value = samples[i]
        if (value !~ /^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ || value + 0 < 0) {
          print "FAIL: " metric_name " contains a non-numeric or negative sample."
          return 0
        }
        if (i == 1 || value + 0 < sample_min) sample_min = value + 0
        if (i == 1 || value + 0 > sample_max) sample_max = value + 0
        if (value + 0 < minimum + 0 || value + 0 > maximum + 0) {
          print "FAIL: " metric_name " sample falls outside its reported minimum/maximum."
          return 0
        }
        sample_mean += value + 0
        sorted_samples[i] = value + 0
      }
      sample_mean /= sample_count
      if (sample_min != minimum + 0 || sample_max != maximum + 0) {
        print "FAIL: " metric_name " reported minimum/maximum do not match the sample extrema."
        return 0
      }

      for (i = 1; i < sample_count; i++) {
        min_index = i
        for (j = i + 1; j <= sample_count; j++) {
          if (sorted_samples[j] < sorted_samples[min_index]) min_index = j
        }
        if (min_index != i) {
          swap_value = sorted_samples[i]
          sorted_samples[i] = sorted_samples[min_index]
          sorted_samples[min_index] = swap_value
        }
      }
      median_position = 0.5 * (sample_count - 1)
      lower_index = int(median_position) + 1
      upper_index = lower_index + 1
      if (upper_index > sample_count) upper_index = sample_count
      interpolation_ratio = median_position - int(median_position)
      expected_median = sorted_samples[lower_index] * (1 - interpolation_ratio) + sorted_samples[upper_index] * interpolation_ratio
      median_difference = median + 0 - expected_median
      if (median_difference < 0) median_difference = -median_difference
      median_tolerance = 0.000001 * (1 + expected_median)
      if (median_difference > median_tolerance) {
        print "FAIL: " metric_name " reported median does not match the 50th percentile of its per-iteration runs."
        return 0
      }

      squared_deviation_sum = 0
      for (i = 1; i <= sample_count; i++) squared_deviation_sum += (samples[i] + 0 - sample_mean) ^ 2
      if (sample_mean == 0) expected_cv = 0
      else expected_cv = sqrt(squared_deviation_sum / (sample_count - 1)) / sample_mean
      cv_difference = coefficient_variation + 0 - expected_cv
      if (cv_difference < 0) cv_difference = -cv_difference
      cv_tolerance = 0.000001 * (1 + expected_cv)
      if (cv_difference > cv_tolerance) {
        print "FAIL: " metric_name " reported coefficientOfVariation does not match its per-iteration runs."
        return 0
      }
      return 1
    }

    { json = json $0 "\n" }

    END {
      expected_iterations = 10
      count_no = count_matches(json, "\"name\"[[:space:]]*:[[:space:]]*\"startupNoCompilation\"")
      count_partial = count_matches(json, "\"name\"[[:space:]]*:[[:space:]]*\"startupPartialWithoutBaselineProfile\"")
      count_profile = count_matches(json, "\"name\"[[:space:]]*:[[:space:]]*\"startupWithBaselineProfile\"")
      count_class = count_matches(json, "\"className\"[[:space:]]*:[[:space:]]*\"com[.]nuvio[.]android[.]baselineprofile[.]StartupBenchmark\"")
      if (count_no != 1 || count_partial != 1 || count_profile != 1 || count_class != 3) {
        print "FAIL: JSON must contain exactly one result for each of the three startup cases and three StartupBenchmark class records."
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
      if (repeat_count != 3) {
        print "FAIL: JSON must contain repeatIterations for all three startup results."
        exit 1
      }

      # AndroidX BenchmarkData stores SDK only at context.build.version.sdk.
      # Require that exact nested field; an unrelated metric or top-level "sdk"
      # property must not qualify the result as a supported phone capture.
      reported_api = ""
      build_start = index(json, "\"build\"")
      if (build_start > 0) {
        build_rest = substr(json, build_start + length("\"build\""))
        sub(/^[[:space:]]*:[[:space:]]*/, "", build_rest)
        if (substr(build_rest, 1, 1) == "{") {
          build_close = index(build_rest, "}")
          if (build_close > 0) {
            build_object = substr(build_rest, 1, build_close)
            version_start = index(build_object, "\"version\"")
            if (version_start > 0) {
              version_rest = substr(build_object, version_start + length("\"version\""))
              sub(/^[[:space:]]*:[[:space:]]*/, "", version_rest)
              if (substr(version_rest, 1, 1) == "{") {
                version_close = index(version_rest, "}")
                if (version_close > 0) {
                  version_object = substr(version_rest, 1, version_close)
                  reported_api = metric_field(version_object, "sdk")
                }
              }
            }
          }
        }
      }
      if (reported_api == "" || reported_api !~ /^[0-9]+$/ || reported_api + 0 < 33) {
        print "FAIL: Macrobenchmark JSON must report context.build.version.sdk as Android API 33 or newer for this phone runner; top-level or unrelated sdk fields are ignored."
        exit 1
      }
      ttfd_metric_pattern = "\"timeToFullDisplayMs\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
      ttfd_rest = json
      ttfd_metric_count = 0
      while (match(ttfd_rest, ttfd_metric_pattern)) {
        ttfd_metric_count++
        ttfd_rest = substr(ttfd_rest, RSTART + RLENGTH)
      }
      if (ttfd_metric_count != 3) {
        print "FAIL: JSON must contain exactly three timeToFullDisplayMs metric objects, one for each startup comparison arm; found " ttfd_metric_count "."
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
        if (metric_count != 3) {
          print "FAIL: JSON must contain exactly three " metric_name " results, one per startup comparison arm."
          exit 1
        }
      }
      if (total_metric_objects != 6) {
        print "FAIL: JSON must contain exactly six startup metric objects."
        exit 1
      }
      art_metric_names[1] = "artJitCount"
      art_metric_names[2] = "artJitSumMs"
      art_metric_names[3] = "artVerifyClassCount"
      art_metric_names[4] = "artVerifyClassSumMs"
      for (art_metric_index = 1; art_metric_index <= 4; art_metric_index++) {
        metric_name = art_metric_names[art_metric_index]
        metric_pattern = "\"" metric_name "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
        metric_rest = json
        metric_count = 0
        while (match(metric_rest, metric_pattern)) {
          metric_start = RSTART
          metric_length = RLENGTH
          metric_object = substr(metric_rest, metric_start, metric_length)
          if (!validate_art_metric_object(metric_object, metric_name)) exit 1
          metric_count++
          metric_rest = substr(metric_rest, metric_start + metric_length)
        }
        if (metric_count != 3) {
          print "FAIL: JSON must contain exactly three " metric_name " results, one per startup comparison arm."
          exit 1
        }
      }

      class_load_count = count_matches(json, "\"artClassLoadCount\"[[:space:]]*:")
      class_load_time_count = count_matches(json, "\"artClassLoadSumMs\"[[:space:]]*:")
      if ((class_load_count == 0 && class_load_time_count != 0) ||
          (class_load_count != 0 && (class_load_count != 3 || class_load_time_count != 3))) {
        print "FAIL: optional ART class-load metrics must be emitted as a complete pair for all three startup cases or omitted entirely."
        exit 1
      }
      if (class_load_count == 3) {
        for (art_metric_index = 1; art_metric_index <= 2; art_metric_index++) {
          if (art_metric_index == 1) metric_name = "artClassLoadCount"
          else metric_name = "artClassLoadSumMs"
          metric_pattern = "\"" metric_name "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          metric_rest = json
          metric_count = 0
          while (match(metric_rest, metric_pattern)) {
            metric_start = RSTART
            metric_length = RLENGTH
            metric_object = substr(metric_rest, metric_start, metric_length)
            if (!validate_art_metric_object(metric_object, metric_name)) exit 1
            metric_count++
            metric_rest = substr(metric_rest, metric_start + metric_length)
          }
          if (metric_count != 3) {
            print "FAIL: optional " metric_name " must contain one valid result for each startup case."
            exit 1
          }
        }
      }

      trace_rest = json
      trace_array_count = 0
      trace_array_pattern = "\"profilerOutputs\"[[:space:]]*:[[:space:]]*\\[[^]]*\\]"
      while (match(trace_rest, trace_array_pattern)) {
        trace_start = RSTART
        trace_length = RLENGTH
        trace_array = substr(trace_rest, trace_start, trace_length)
        trace_type_count = count_matches(trace_array, "\"type\"[[:space:]]*:[[:space:]]*\"PerfettoTrace\"")
        trace_filename_count = count_matches(trace_array, "\"filename\"[[:space:]]*:[[:space:]]*\"[^\"]+\"")
        profiler_output_count = count_matches(trace_array, "[{]")
        if (profiler_output_count != expected_iterations) {
          print "FAIL: Macrobenchmark JSON must contain exactly ten profiler output objects per startup case (one PerfettoTrace per measured iteration); additional profiler artifacts are not accepted in a clean timing comparison."
          exit 1
        }
        if (trace_type_count != expected_iterations || trace_filename_count != expected_iterations) {
          print "FAIL: Macrobenchmark JSON clean-run contract requires ten PerfettoTrace entries with filenames per startup case."
          exit 1
        }
        for (trace_iteration = 0; trace_iteration < expected_iterations; trace_iteration++) {
          trace_label_pattern = "\"label\"[[:space:]]*:[[:space:]]*\"Trace Iteration " trace_iteration "\""
          if (count_matches(trace_array, trace_label_pattern) != 1) {
            print "FAIL: each startup result must contain exactly one trace reference for every measured iteration."
            exit 1
          }
          trace_object_pattern = "\\{[^}]*\"label\"[[:space:]]*:[[:space:]]*\"Trace Iteration " trace_iteration "\"[^}]*\\}"
          if (!match(trace_array, trace_object_pattern)) {
            print "FAIL: Macrobenchmark JSON is missing the profiler output object for Trace Iteration " trace_iteration "."
            exit 1
          }
          trace_object = substr(trace_array, RSTART, RLENGTH)
          if (count_matches(trace_object, "\"type\"[[:space:]]*:[[:space:]]*\"PerfettoTrace\"") != 1 ||
              count_matches(trace_object, "\"filename\"[[:space:]]*:[[:space:]]*\"[^\"]+\"") != 1) {
            print "FAIL: Trace Iteration " trace_iteration " must itself be one PerfettoTrace object with exactly one filename."
            exit 1
          }
        }
        trace_array_count++
        trace_rest = substr(trace_rest, trace_start + trace_length)
      }
      if (trace_array_count != 3) {
        print "FAIL: JSON must contain profilerOutputs for all three startup comparison results."
        exit 1
      }
      print "PASS: all three startup results contain ten numeric TTID/TTFD and ART samples, valid statistics and ten Perfetto trace references each."
    }
  ' "$1"
}

extract_macrobenchmark_trace_filenames() {
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
  [ "$reference_count" -eq 30 ] || fail "Macrobenchmark trace output must reference thirty per-iteration Perfetto files (ten per comparison arm); found $reference_count references."

  unique_reference_count="$(sort -u "$trace_reference_file" | wc -l | tr -d ' ')"
  [ "$unique_reference_count" -eq 30 ] || fail "Macrobenchmark trace output contains duplicate filenames; expected thirty unique Perfetto traces, found $unique_reference_count."

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
  [ "$trace_file_count" -eq 30 ] || fail "Macrobenchmark trace output must contain exactly thirty non-empty per-iteration Perfetto traces; found $trace_file_count."
}

write_macrobenchmark_summary() {
  result_file="$1"
  summary_file="$RUN_DIR/benchmark-summary.txt"
  awk '
    function metric_field(object, field,    needle, start, rest) {
      needle = "\"" field "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?/)) return substr(rest, 1, RLENGTH)
      return ""
    }

    function metric_string_field(object, field,    needle, start, rest) {
      needle = "\"" field "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^"[^"]*"/)) return substr(rest, 2, RLENGTH - 2)
      return ""
    }

    function metric_boolean_field(object, field,    needle, start, rest) {
      needle = "\"" field "\""
      start = index(object, needle)
      if (start == 0) return ""
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (match(rest, /^(true|false)/)) return substr(rest, 1, RLENGTH)
      return ""
    }

    function metric_runs(object, values,    needle, start, rest, close_pos, run_text) {
      needle = "\"runs\""
      start = index(object, needle)
      if (start == 0) return 0
      rest = substr(object, start + length(needle))
      sub(/^[[:space:]]*:[[:space:]]*/, "", rest)
      if (substr(rest, 1, 1) != "[") return 0
      close_pos = index(rest, "]")
      if (close_pos == 0) return 0
      run_text = substr(rest, 2, close_pos - 2)
      gsub(/[[:space:]]/, "", run_text)
      if (run_text == "") return 0
      return split(run_text, values, ",")
    }

    function trace_filename(result_object, iteration,    array_pattern, trace_array, trace_pattern, trace_object) {
      array_pattern = "\"profilerOutputs\"[[:space:]]*:[[:space:]]*\\[[^]]*\\]"
      if (!match(result_object, array_pattern)) return ""
      trace_array = substr(result_object, RSTART, RLENGTH)
      trace_pattern = "\\{[^}]*\"label\"[[:space:]]*:[[:space:]]*\"Trace Iteration " iteration "\"[^}]*\\}"
      if (!match(trace_array, trace_pattern)) return ""
      trace_object = substr(trace_array, RSTART, RLENGTH)
      return metric_string_field(trace_object, "filename")
    }

    { json = json $0 "\n" }

    END {
      expected_iterations = 10
      test_names[1] = "startupNoCompilation"
      test_names[2] = "startupPartialWithoutBaselineProfile"
      test_names[3] = "startupWithBaselineProfile"
      metric_names[1] = "timeToInitialDisplayMs"
      metric_names[2] = "timeToFullDisplayMs"

      device_model = metric_string_field(json, "model")
      android_api = metric_field(json, "sdk")
      cpu_core_count = metric_field(json, "cpuCoreCount")
      cpu_max_frequency = metric_field(json, "cpuMaxFreqHz")
      cpu_locked = metric_boolean_field(json, "cpuLocked")
      memory_total = metric_field(json, "memTotalBytes")
      art_mainline_version = metric_field(json, "artMainlineVersion")
      sustained_performance = metric_boolean_field(json, "sustainedPerformanceModeEnabled")
      os_codename = metric_string_field(json, "osCodenameAbbreviated")
      if (device_model == "") device_model = "not reported"
      if (android_api == "") android_api = "not reported"
      if (cpu_core_count == "") cpu_core_count = "not reported"
      if (cpu_max_frequency == "") cpu_max_frequency = "not reported"
      if (cpu_locked == "") cpu_locked = "not reported"
      if (memory_total == "") memory_total = "not reported"
      if (art_mainline_version == "") art_mainline_version = "not reported"
      if (sustained_performance == "") sustained_performance = "not reported"
      if (os_codename == "") os_codename = "not reported"

      for (test_index = 1; test_index <= 3; test_index++) {
        test_pattern = "\"name\"[[:space:]]*:[[:space:]]*\"" test_names[test_index] "\""
        if (!match(json, test_pattern)) {
          print "FAIL: cannot find " test_names[test_index] " while writing benchmark summary."
          exit 1
        }
        result_rest = substr(json, RSTART + RLENGTH)
        next_test_pattern = "\"name\"[[:space:]]*:[[:space:]]*\"[^\"]+\""
        if (match(result_rest, next_test_pattern)) result_object = substr(result_rest, 1, RSTART - 1)
        else result_object = result_rest
        expected_warmup_iterations = 0
        if (test_index == 2) expected_warmup_iterations = 3
        actual_warmup_iterations = metric_field(result_object, "warmupIterations")
        if (actual_warmup_iterations == "" || actual_warmup_iterations + 0 != expected_warmup_iterations) {
          print "FAIL: Macrobenchmark JSON must report warmupIterations=" expected_warmup_iterations " for " test_names[test_index] "."
          exit 1
        }
        test_objects[test_index] = result_object

        for (metric_index = 1; metric_index <= 2; metric_index++) {
          metric_pattern = "\"" metric_names[metric_index] "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          if (!match(result_object, metric_pattern)) {
            print "FAIL: cannot find " metric_names[metric_index] " for " test_names[test_index] " while writing benchmark summary."
            exit 1
          }
          metric_object = substr(result_object, RSTART, RLENGTH)
          metric_key = test_names[test_index] SUBSEP metric_names[metric_index]
          minimum[metric_key] = metric_field(metric_object, "minimum") + 0
          median[metric_key] = metric_field(metric_object, "median") + 0
          maximum[metric_key] = metric_field(metric_object, "maximum") + 0
          cv[metric_key] = metric_field(metric_object, "coefficientOfVariation") + 0

          run_count = metric_runs(metric_object, run_values)
          if (run_count != expected_iterations) {
            print "FAIL: " metric_names[metric_index] " for " test_names[test_index] " must contain exactly " expected_iterations " raw samples."
            exit 1
          }
          for (run_index = 1; run_index <= run_count; run_index++) {
            if (run_values[run_index] !~ /^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ || run_values[run_index] + 0 <= 0) {
              print "FAIL: " metric_names[metric_index] " for " test_names[test_index] " contains an invalid raw sample."
              exit 1
            }
            if (metric_index == 1) ttid_runs[test_index, run_index] = run_values[run_index] + 0
            else ttfd_runs[test_index, run_index] = run_values[run_index] + 0
          }
        }
      }

      art_metric_names[1] = "artJitCount"
      art_metric_names[2] = "artJitSumMs"
      art_metric_names[3] = "artVerifyClassCount"
      art_metric_names[4] = "artVerifyClassSumMs"
      for (test_index = 1; test_index <= 3; test_index++) {
        result_object = test_objects[test_index]
        for (art_index = 1; art_index <= 4; art_index++) {
          metric_name = art_metric_names[art_index]
          metric_pattern = "\"" metric_name "\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          if (!match(result_object, metric_pattern)) {
            print "FAIL: cannot find " metric_name " for " test_names[test_index] " while writing ART summary."
            exit 1
          }
          metric_object = substr(result_object, RSTART, RLENGTH)
          art_median[test_index, art_index] = metric_field(metric_object, "median") + 0
        }
        class_load_count_pattern = "\"artClassLoadCount\"[[:space:]]*:"
        class_load_time_pattern = "\"artClassLoadSumMs\"[[:space:]]*:"
        if (match(result_object, class_load_count_pattern) && match(result_object, class_load_time_pattern)) {
          class_load_count_object_pattern = "\"artClassLoadCount\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          class_load_time_object_pattern = "\"artClassLoadSumMs\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          match(result_object, class_load_count_object_pattern)
          class_load_count_object = substr(result_object, RSTART, RLENGTH)
          match(result_object, class_load_time_object_pattern)
          class_load_time_object = substr(result_object, RSTART, RLENGTH)
          art_class_load_count[test_index] = metric_field(class_load_count_object, "median") + 0
          art_class_load_ms[test_index] = metric_field(class_load_time_object, "median") + 0
          class_load_metrics_available = 1
        }
      }

      print "Nuvio Android cold-start benchmark"
      print "Benchmark device context"
      printf "  Device: %s | Android API: %s\n", device_model, android_api
      printf "  CPU: %s cores | max frequency %s Hz | clocks locked: %s\n", cpu_core_count, cpu_max_frequency, cpu_locked
      printf "  Memory: %s bytes | ART Mainline: %s | sustained performance mode: %s\n", memory_total, art_mainline_version, sustained_performance
      printf "  Android release codename: %s\n", os_codename
      print "  Install path: APK sideload via pm install; not Google Play delivery."
      print "  The playstore build-flavor label does not mean this APK was installed from Google Play."
      if (android_api ~ /^[0-9]+$/) {
        if (android_api + 0 == 33) {
          print "  ART comparability: API 33 uses the pre-34 reset path; unrooted user builds reinstall for CompilationMode.None."
          print "  API 33 warning: do not treat this as a Play-equivalent isolated Baseline Profile comparison; ART verification state can differ."
        } else if (android_api + 0 >= 34) {
          print "  ART comparability: API 34+ CompilationMode.None uses verify and clears app ART profiles before measurement."
          print "  This controls the pre-34 verification-state confounder; the APK is still sideloaded, not Play-delivered."
        } else {
          print "  ART comparability: API below 33 is outside this runner supported range."
        }
      } else {
        print "  ART comparability: Android API unavailable; do not infer Play-equivalent verification state."
      }
      print ""
      print "Comparison: no compilation, profile-disabled partial compilation, and Baseline Profile"
      print "The profile-disabled partial arm uses three warmup iterations before measurement."
      print "10 samples per comparison arm"
      print ""
      for (metric_index = 1; metric_index <= 2; metric_index++) {
        metric = metric_names[metric_index]
        no_key = test_names[1] SUBSEP metric
        partial_key = test_names[2] SUBSEP metric
        profile_key = test_names[3] SUBSEP metric
        partial_delta_ms = median[partial_key] - median[no_key]
        partial_delta_percent = partial_delta_ms / median[no_key] * 100
        profile_delta_ms = median[profile_key] - median[partial_key]
        profile_delta_percent = profile_delta_ms / median[partial_key] * 100
        total_delta_ms = median[profile_key] - median[no_key]
        total_delta_percent = total_delta_ms / median[no_key] * 100
        print metric
        printf "  No compilation: median %.1f ms (range %.1f..%.1f ms; CV %.2f%%)\n", median[no_key], minimum[no_key], maximum[no_key], cv[no_key] * 100
        printf "  Partial without Baseline Profile (3 warmups): median %.1f ms (range %.1f..%.1f ms; CV %.2f%%)\n", median[partial_key], minimum[partial_key], maximum[partial_key], cv[partial_key] * 100
        printf "  Baseline Profile: median %.1f ms (range %.1f..%.1f ms; CV %.2f%%)\n", median[profile_key], minimum[profile_key], maximum[profile_key], cv[profile_key] * 100
        printf "  Difference (partial without profile - no compilation): %+.1f ms (%+.1f%%)\n", partial_delta_ms, partial_delta_percent
        printf "  Incremental Baseline Profile difference (profile - partial without profile): %+.1f ms (%+.1f%%)\n", profile_delta_ms, profile_delta_percent
        printf "  Total difference (profile - no compilation): %+.1f ms (%+.1f%%)\n", total_delta_ms, total_delta_percent
        print ""
      }

      print "ART runtime compilation and verification (median per cold launch)"
      printf "  No compilation: JIT %.1f methods / %.2f ms; runtime verification %.1f classes / %.2f ms\n", art_median[1,1], art_median[1,2], art_median[1,3], art_median[1,4]
      printf "  Partial without Baseline Profile: JIT %.1f methods / %.2f ms; runtime verification %.1f classes / %.2f ms\n", art_median[2,1], art_median[2,2], art_median[2,3], art_median[2,4]
      printf "  Baseline Profile: JIT %.1f methods / %.2f ms; runtime verification %.1f classes / %.2f ms\n", art_median[3,1], art_median[3,2], art_median[3,3], art_median[3,4]
      if (class_load_metrics_available) {
        printf "  Runtime class loading: no compilation %.1f classes / %.2f ms; partial without profile %.1f classes / %.2f ms; Baseline Profile %.1f classes / %.2f ms\n", art_class_load_count[1], art_class_load_ms[1], art_class_load_count[2], art_class_load_ms[2], art_class_load_count[3], art_class_load_ms[3]
      } else {
        print "  Runtime class loading: not emitted (ART class-load tracepoints unavailable on this device)"
      }
      print ""

      print "Per-iteration samples and matching Perfetto traces"
      print "AndroidX preserves measurement order in the raw runs and labels the corresponding trace with the same iteration index."
      print ""
      for (test_index = 1; test_index <= 3; test_index++) {
        print test_names[test_index]
        for (run_index = 1; run_index <= expected_iterations; run_index++) {
          iteration = run_index - 1
          trace = trace_filename(test_objects[test_index], iteration)
          if (trace == "") {
            print "FAIL: missing Perfetto trace filename for " test_names[test_index] " iteration " iteration "."
            exit 1
          }
          printf "  Iteration %d: TTID %.1f ms | TTFD %.1f ms | trace %s\n", iteration, ttid_runs[test_index, run_index], ttfd_runs[test_index, run_index], trace
        }
        print ""
      }
      print "Interpretation: descriptive only; ten iterations do not establish statistical significance."
      print "Do not claim a speedup from the median difference alone. Inspect per-iteration traces for skew, thermal throttling, and warm-up effects."
    }
  ' "$result_file" > "$summary_file" || fail "Could not create the human-readable Macrobenchmark summary."
  [ -s "$summary_file" ] || fail "Macrobenchmark summary is empty."
}

append_macrobenchmark_execution_order() {
  execution_log="$1"
  awk '
    /^INSTRUMENTATION_STATUS: test=/ {
      test_name = $0
      sub(/^INSTRUMENTATION_STATUS: test=/, "", test_name)
      if (test_name == "startupNoCompilation" ||
          test_name == "startupPartialWithoutBaselineProfile" ||
          test_name == "startupWithBaselineProfile") {
        if (!(test_name in seen)) {
          seen[test_name] = 1
          execution_order[++execution_count] = test_name
        }
      }
    }
    END {
      if (execution_count != 3) {
        printf "FAIL: expected the three startup comparison cases in the instrumentation execution log; found %d.\n", execution_count > "/dev/stderr"
        exit 1
      }
      printf "\nMacrobenchmark JUnit execution order (from AndroidJUnitRunner status records)\n"
      for (position = 1; position <= execution_count; position++) {
        printf "  %d. %s\n", position, execution_order[position]
      }
    }
  ' "$execution_log" >> "$RUN_DIR/benchmark-summary.txt" || fail "Could not derive the actual Macrobenchmark JUnit execution order from instrumentation.log."
}

require_macrobenchmark_result_data() {
  json_files=0
  no_compilation_results=0
  partial_without_profile_results=0
  baseline_profile_results=0
  ttid_metrics=0
  ttfd_metrics=0
  summary_result_file=""

  for result_file in "$RUN_DIR"/*benchmarkData.json; do
    [ -f "$result_file" ] || continue
    json_files=$((json_files + 1))
    [ -s "$result_file" ] || fail "Macrobenchmark result file is empty: $result_file."
    [ -n "$summary_result_file" ] || summary_result_file="$result_file"

    no_compilation_results=$((no_compilation_results + $(count_json_named_results startupNoCompilation "$result_file")))
    partial_without_profile_results=$((partial_without_profile_results + $(count_json_named_results startupPartialWithoutBaselineProfile "$result_file")))
    baseline_profile_results=$((baseline_profile_results + $(count_json_named_results startupWithBaselineProfile "$result_file")))
    ttid_metrics=$((ttid_metrics + $(count_json_occurrences '"timeToInitialDisplayMs"' "$result_file")))
    ttfd_metrics=$((ttfd_metrics + $(count_json_occurrences '"timeToFullDisplayMs"' "$result_file")))
    validate_macrobenchmark_statistics "$result_file" || fail "Macrobenchmark JSON contains incomplete or invalid per-iteration startup statistics: $result_file."
    validate_macrobenchmark_trace_files "$result_file"
  done

  [ "$json_files" -eq 1 ] || fail "Expected exactly one Macrobenchmark benchmarkData.json file for this instrumentation run; found $json_files. A single startup benchmark invocation must produce one module-level result report; multiple reports make summary selection ambiguous."
  [ "$no_compilation_results" -ge 1 ] && [ "$partial_without_profile_results" -ge 1 ] && [ "$baseline_profile_results" -ge 1 ] || {
    fail "Macrobenchmark JSON must contain all three startup comparison results (found no-compilation=$no_compilation_results, partial-without-profile=$partial_without_profile_results, Baseline-Profile=$baseline_profile_results)."
  }
  [ "$ttid_metrics" -ge 3 ] && [ "$ttfd_metrics" -ge 3 ] || {
    fail "Macrobenchmark JSON must contain TTID and TTFD metrics for all three startup results (found $ttid_metrics and $ttfd_metrics)."
  }
  write_macrobenchmark_summary "$summary_result_file"
  {
    printf '\nThermal integrity\n'
    cat "$RUN_DIR/thermal-integrity.txt"
  } >> "$RUN_DIR/benchmark-summary.txt" || fail "Could not append thermal integrity to the benchmark summary."
  printf 'Generated thermal integrity -> %s\n' "$RUN_DIR/thermal-integrity.txt" >> "$RUN_DIR/copied-files.txt"
  {
    printf '\nBattery Saver integrity\n'
    cat "$RUN_DIR/battery-saver-integrity.txt"
  } >> "$RUN_DIR/benchmark-summary.txt" || fail "Could not append Battery Saver integrity to the benchmark summary."
  printf 'Generated Battery Saver integrity -> %s\n' "$RUN_DIR/battery-saver-integrity.txt" >> "$RUN_DIR/copied-files.txt"
  append_macrobenchmark_execution_order "$RUN_DIR/instrumentation.log"
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
  app_source_hash="$(file_sha256 "$APP_APK")"
  test_source_hash="$(file_sha256 "$TEST_APK")"
  [ "$app_source_hash" != unavailable ] && [ "$test_source_hash" != unavailable ] || fail "Could not calculate SHA-256 for the installed app/test APK pair."
  {
    printf 'Variant: %s:%s\n' "$ACTION" "$FLAVOR"
    printf 'App APK source filename: %s\nApp APK source SHA-256: %s\n' "$(basename "$APP_APK")" "$app_source_hash"
    printf 'Test APK source filename: %s\nTest APK source SHA-256: %s\n' "$(basename "$TEST_APK")" "$test_source_hash"
  } > "$(install_provenance_file)" || fail "Could not persist APK install provenance."
  printf '\nInstalled %s (%s). Open Nuvio, sign in, finish onboarding if needed, and leave it on Home.\n' "$FLAVOR" "$ACTION"
  printf 'For profile collection, Home must contain at least one catalogue shelf with enough entries to scroll horizontally; the home_browse profile fails if it is absent.\n'
  printf 'Do not clear app data between full and playstore; both flavors share the isolated .benchmark app ID and signing key.\n'
  printf 'Production app com.nuvio.media and its data are separate and were not changed.\n'
}

require_installed_variant() {
  expected="$1:$FLAVOR"
  [ -f "$OUTPUT_ROOT/current-install.txt" ] || fail "No benchmark variant state was recorded. Run the matching install action first."
  installed="$(cat "$OUTPUT_ROOT/current-install.txt")"
  [ "$installed" = "$expected" ] || fail "Currently installed variant is '$installed', but '$expected' is required. Run the matching install action first."
}

load_expected_install_provenance() {
  expected_action="$1"
  expected_variant="$expected_action:$FLAVOR"
  [ -f "$OUTPUT_ROOT/current-install.txt" ] || fail "No benchmark variant state was recorded. Run the matching install action first."
  installed="$(cat "$OUTPUT_ROOT/current-install.txt")"
  [ "$installed" = "$expected_variant" ] || fail "Currently installed variant is '$installed', but '$expected_variant' is required. Run the matching install action first."
  load_install_provenance "$expected_variant"
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

read_thermal_status_from_snapshot() {
  snapshot_file="$1"
  [ -r "$snapshot_file" ] || {
    printf 'unknown\n'
    return 0
  }
  awk '
    tolower($0) ~ /^[[:space:]]*thermal status:[[:space:]]*[0-6]([[:space:]]|$)/ {
      status_line = $0
      sub(/^[^:]*:[[:space:]]*/, "", status_line)
      sub(/[[:space:]].*$/, "", status_line)
      if (status_line ~ /^[0-6]$/) {
        print status_line
        found = 1
        exit
      }
    }
    END {
      if (!found) print "unknown"
    }
  ' "$snapshot_file"
}

read_battery_saver_from_snapshot() {
  snapshot_file="$1"
  [ -r "$snapshot_file" ] || {
    printf 'unknown\n'
    return 0
  }
  awk '
    {
      line = tolower($0)
      sub(/^[[:space:]]*/, "", line)
      if (index(line, "battery saver (settings global low_power):") == 1) {
        status_line = line
        sub(/^.*:[[:space:]]*/, "", status_line)
        sub(/[[:space:]].*$/, "", status_line)
        if (status_line == "0" || status_line == "1") {
          print status_line
          found = 1
          exit
        }
      }
    }
    END {
      if (!found) print "unknown"
    }
  ' "$snapshot_file"
}

battery_saver_status_label() {
  case "$1" in
    0) printf '0 (OFF)' ;;
    1) printf '1 (ON)' ;;
    *) printf 'unknown' ;;
  esac
}

thermal_status_label() {
  case "$1" in
    0) printf '0 (NONE)' ;;
    1) printf '1 (LIGHT)' ;;
    2) printf '2 (MODERATE)' ;;
    3) printf '3 (SEVERE)' ;;
    4) printf '4 (CRITICAL)' ;;
    5) printf '5 (EMERGENCY)' ;;
    6) printf '6 (SHUTDOWN)' ;;
    *) printf 'unknown' ;;
  esac
}

write_thermal_integrity_summary() {
  before_status="$1"
  after_status="$2"
  thermal_report="$RUN_DIR/thermal-integrity.txt"
  {
    printf 'Thermal status before measurement: %s\n' "$(thermal_status_label "$before_status")"
    printf 'Thermal status after measurement: %s\n' "$(thermal_status_label "$after_status")"
    case "$after_status" in
      2|3|4|5|6)
        printf 'VERDICT: THERMALLY CONTAMINATED\n'
        printf 'Do not interpret these timings as an unthrottled startup comparison; the post-run thermal status is moderate or higher.\n'
        ;;
      unknown)
        printf 'VERDICT: THERMAL STATUS UNKNOWN\n'
        printf 'Thermal integrity could not be verified from the device snapshots.\n'
        ;;
      *)
        case "$before_status" in
          unknown)
            printf 'VERDICT: THERMAL STATUS UNKNOWN\n'
            printf 'The pre-run thermal status could not be verified from the device snapshot.\n'
            ;;
          *)
            printf 'VERDICT: NO MODERATE-OR-HIGHER THERMAL STATUS OBSERVED AT THE TWO SNAPSHOTS\n'
            ;;
        esac
        ;;
    esac
    printf 'Limit: only the pre-run and post-run snapshots are checked; transient thermal-status changes between captures cannot be excluded.\n'
  } > "$thermal_report" || fail "Could not write thermal integrity report: $thermal_report."
}

write_battery_saver_integrity_summary() {
  before_status="$1"
  after_status="$2"
  battery_saver_report="$RUN_DIR/battery-saver-integrity.txt"
  {
    printf 'Battery Saver state before measurement: %s\n' "$(battery_saver_status_label "$before_status")"
    printf 'Battery Saver state after measurement: %s\n' "$(battery_saver_status_label "$after_status")"
    case "$after_status" in
      1)
        printf 'VERDICT: BATTERY-SAVER CONTAMINATED\n'
        printf 'Do not compare these timings as a normal-power startup run; Android low-power mode was active at the post-run capture.\n'
        ;;
      unknown)
        printf 'VERDICT: BATTERY SAVER STATUS UNKNOWN\n'
        printf 'Battery Saver state could not be verified at the post-run capture.\n'
        ;;
      0)
        case "$before_status" in
          unknown)
            printf 'VERDICT: BATTERY SAVER STATUS UNKNOWN\n'
            printf 'Battery Saver state could not be verified at the pre-run capture.\n'
            ;;
          *)
            printf 'VERDICT: BATTERY SAVER OFF AT THE TWO SNAPSHOTS\n'
            ;;
        esac
        ;;
    esac
    printf 'Limit: only the pre-run and post-run settings snapshots are checked; transient state changes between captures cannot be excluded.\n'
  } > "$battery_saver_report" || fail "Could not write Battery Saver integrity report: $battery_saver_report."
}

capture_foreground_state() {
  label="$1"
  snapshot="$RUN_DIR/foreground-$label.txt"
  {
    printf 'Capture: %s\n' "$label"
    printf 'Timestamp: '
    date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'unavailable\n'
    printf '\nWindow focus (mCurrentFocus / mFocusedApp):\n'
    if command -v dumpsys >/dev/null 2>&1; then
      dumpsys window windows 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' || true
      printf '\nActivity state (top resumed / resumed):\n'
      dumpsys activity activities 2>&1 | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' || true
    else
      printf 'dumpsys unavailable\n'
    fi
  } > "$snapshot" 2>&1 || {
    printf 'could not write foreground snapshot: %s\n' "$snapshot" >&2
    return 0
  }
}

reject_pending_wifi_debug_confirmation() {
  focus_state_file="$RUN_DIR/wireless-debug-focus-check.txt"
  {
    dumpsys window windows 2>&1
    dumpsys activity activities 2>&1
  } > "$focus_state_file"
  if grep -Eiq 'WifiDebuggingActivity|mCurrentFocus=.*com[.]android[.]systemui|topResumedActivity=.*com[.]android[.]systemui' "$focus_state_file"; then
    grep -Ei 'WifiDebuggingActivity|mCurrentFocus|mFocusedApp|topResumedActivity|mResumedActivity' "$focus_state_file" | head -n 12 >&2 || true
    fail "Wireless debugging confirmation is active in Android System UI. Unlock the phone, approve or dismiss the Wireless debugging prompt for this LADB session, then rerun the benchmark. Instrumentation was not started."
  fi
  rm -f "$focus_state_file"
}

run_instrumentation() {
  rule="$1"
  class_name="$2"
  case "$rule" in
    BaselineProfile) require_installed_variant install-profile ;;
    macrobenchmark) require_installed_variant install ;;
  esac
  new_run_dir
  capture_foreground_state before
  reject_pending_wifi_debug_confirmation
  resolve_instrumentation
  case "$rule" in
    BaselineProfile) load_expected_install_provenance install-profile ;;
    macrobenchmark) load_expected_install_provenance install ;;
  esac
  write_run_provenance
  printf 'Running %s on %s. Keep the phone cool, plugged in, and otherwise idle.\n' "$rule" "$FLAVOR"
  if [ "$rule" = macrobenchmark ]; then
    capture_device_environment before
    THERMAL_STATUS_BEFORE="$(read_thermal_status_from_snapshot "$RUN_DIR/environment-before.txt")"
    BATTERY_SAVER_BEFORE="$(read_battery_saver_from_snapshot "$RUN_DIR/environment-before.txt")"
    case "$THERMAL_STATUS_BEFORE" in
      2|3|4|5|6)
        fail "Thermal status $(thermal_status_label "$THERMAL_STATUS_BEFORE") is already active before Macrobenchmark. Moderate-or-higher throttling can affect foreground performance. Cool the phone until status is 0 or 1, then retry; instrumentation was not started."
        ;;
    esac
    case "$BATTERY_SAVER_BEFORE" in
      1)
        fail "Battery Saver is enabled before Macrobenchmark (settings global low_power=1). Android low-power mode is intended to save battery at the cost of performance. Disable Battery Saver and retry; instrumentation was not started."
        ;;
    esac
  fi
  set +e
  am instrument -w -r \
    -e androidx.benchmark.enabledRules "$rule" \
    -e class "$class_name" \
    "$INSTRUMENTATION" > "$RUN_DIR/instrumentation.log" 2>&1
  result=$?
  set -e
  capture_foreground_state after
  if [ "$rule" = macrobenchmark ]; then
    capture_device_environment after
    THERMAL_STATUS_AFTER="$(read_thermal_status_from_snapshot "$RUN_DIR/environment-after.txt")"
    BATTERY_SAVER_AFTER="$(read_battery_saver_from_snapshot "$RUN_DIR/environment-after.txt")"
    write_thermal_integrity_summary "$THERMAL_STATUS_BEFORE" "$THERMAL_STATUS_AFTER"
    write_battery_saver_integrity_summary "$BATTERY_SAVER_BEFORE" "$BATTERY_SAVER_AFTER"
  fi
  cat "$RUN_DIR/instrumentation.log"
  [ "$result" -eq 0 ] || fail "Instrumentation returned exit code $result. See $RUN_DIR/instrumentation.log and foreground-before.txt / foreground-after.txt in $RUN_DIR. These snapshots distinguish a Nuvio readiness timeout from an external Android System UI or LADB interruption."
  if grep -Eiq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]' "$RUN_DIR/instrumentation.log"; then
    fail "The test reported a failure. See $RUN_DIR/instrumentation.log."
  fi
  case "$rule" in
    BaselineProfile)
      copy_recent_outputs profile
      require_non_empty_startup_profile
      require_non_empty_home_browse_profile
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
