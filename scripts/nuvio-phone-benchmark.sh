#!/system/bin/sh
# Phone-only Nuvio startup benchmark runner for LADB / Android shell.
# Keep this script and all eight arm64-v8a APKs in the same Downloads folder.
set -eu

APP_PACKAGE="com.nuvio.media.benchmark"
# Match AndroidX Shell.pgrepLF: API 36+ requires -a for the full command line.
ANDROIDX_PROCESS_PATTERN='com\.nuvio\.media\.benchmark'
TEST_PACKAGE="com.nuvio.android.baselineprofile"
GENERATOR_CLASS="com.nuvio.android.baselineprofile.BaselineProfileGenerator"
BENCHMARK_CLASS="com.nuvio.android.baselineprofile.StartupBenchmark"
MEMORY_BENCHMARK_CLASS="com.nuvio.android.baselineprofile.StartupMemoryBenchmark"
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
    run:full|install:full|memory-profile:full)
      STAGE_APKS="Nuvio-full-BenchmarkRelease.apk Nuvio-full-BenchmarkRelease-Tests.apk"
      ;;
    run:playstore|install:playstore|memory-profile:playstore)
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
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh memory-profile full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install-profile playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh run playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh memory-profile playstore

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
    run|measure|memory-profile)
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
    full:install|full:collect|full:measure|full:memory-profile)
      APP_APK="$SCRIPT_DIR/Nuvio-full-BenchmarkRelease.apk"
      TEST_APK="$SCRIPT_DIR/Nuvio-full-BenchmarkRelease-Tests.apk"
      ;;
    playstore:install|playstore:collect|playstore:measure|playstore:memory-profile)
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
  run_dir_base="$OUTPUT_ROOT/$FLAVOR/$ACTION-$stamp"
  mkdir -p "$OUTPUT_ROOT/$FLAVOR" || fail "Cannot create the benchmark result folder: $OUTPUT_ROOT/$FLAVOR"

  # Use the platform's atomic temporary-directory allocator. The timestamp
  # remains human-readable; the random suffix prevents concurrent same-second
  # invocations from racing over a shared numeric suffix.
  if command -v mktemp >/dev/null 2>&1; then
    RUN_DIR="$(mktemp -d "${run_dir_base}-XXXXXX" 2>/dev/null)" ||
      fail "Cannot reserve a unique benchmark result directory under: $run_dir_base"
  else
    # Compatibility fallback for minimal shells without mktemp.
    RUN_DIR="$run_dir_base"
    run_dir_suffix=0
    while :; do
      if mkdir "$RUN_DIR" 2>/dev/null; then
        break
      fi
      [ -d "$RUN_DIR" ] || fail "Cannot reserve a unique benchmark result directory: $RUN_DIR"
      run_dir_suffix=$((run_dir_suffix + 1))
      RUN_DIR="$run_dir_base-$run_dir_suffix"
    done
  fi

  MARKER="$RUN_DIR/.started"
  : > "$MARKER" || fail "Cannot create the benchmark run marker: $MARKER"
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

require_recognizable_art_profile_rules() {
  profile_file="$1"
  # Validate the structural HRF markers emitted by ART: optional method flags,
  # a DEX class descriptor, and (for method rules) a method signature. Comments
  # and blank lines are allowed. This is deliberately a sanity check, not a
  # replacement for profman's full descriptor/method resolution.
  if ! awk '
    function is_rule(line, annotation_end, method_separator, prefix, descriptor_start, flags, descriptor, method, open_paren, close_paren, return_type, i) {
      sub(/\r$/, "", line)
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      if (line == "" || substr(line, 1, 1) == "#") return 0
      if (substr(line, 1, 1) == "{") {
        annotation_end = index(line, "}")
        if (annotation_end == 0) return 0
        line = substr(line, annotation_end + 1)
      }
      method_separator = index(line, "->")
      if (method_separator == 0) return line ~ /^\[*L[^;[:space:]]+;$/ || line ~ /^\[*[BCDFIJSZ]$/
      prefix = substr(line, 1, method_separator - 1)
      descriptor_start = 0
      for (i = 1; i <= length(prefix); i++) {
        if (substr(prefix, i, 1) == "L") descriptor_start = i
      }
      if (descriptor_start == 0) return 0
      flags = substr(prefix, 1, descriptor_start - 1)
      if (flags !~ /^[HSP]*$/) return 0
      descriptor = substr(prefix, descriptor_start)
      if (descriptor !~ /^\[*L[^;[:space:]]+;$/) return 0
      method = substr(line, method_separator + 2)
      if (method == "*") return 1
      open_paren = index(method, "(")
      close_paren = index(method, ")")
      if (open_paren <= 1 || close_paren <= open_paren) return 0
      return_type = substr(method, close_paren + 1, 1)
      return (return_type ~ /^[BCDFIJSVZL]$/ || return_type == "[")
    }
    is_rule($0) { valid_rules++ }
    END { exit(valid_rules > 0 ? 0 : 1) }
  ' "$profile_file"; then
    fail "Generated profile contains no recognizable ART profile rules: $profile_file."
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
    require_recognizable_art_profile_rules "$profile_file"
  done
  [ "$startup_profile_count" -gt 0 ] || fail "No startup-prof text profile was produced. Check the Startup Profile collection and $RUN_DIR/instrumentation.log."
}

write_profile_audit_summary() {
  profile_summary="$RUN_DIR/profile-audit-summary.txt"
  profile_count=0
  {
    printf 'Nuvio Baseline Profile artifact audit\n'
    printf 'Read-only inventory; profile rules are not rewritten or normalized.\n\n'
    for profile_file in "$RUN_DIR"/*startup-prof*.txt "$RUN_DIR"/*home_browse-baseline-prof*.txt; do
      [ -f "$profile_file" ] || continue
      profile_count=$((profile_count + 1))
      case "$profile_file" in
        *startup-prof*) profile_label="Startup Profile" ;;
        *home_browse-baseline-prof*) profile_label="Home browse Baseline Profile" ;;
        *) profile_label="Profile artifact" ;;
      esac
      printf '%s\n' "$profile_label"
      printf '  File: %s\n' "$(basename "$profile_file")"
      printf '  SHA-256: %s\n' "$(file_sha256 "$profile_file")"
      awk '
        NF {
          total++
          if (!seen[$0]++) unique++
          rule = $0
          sub(/^[[:space:]]*/, "", rule)
          if (index(rule, "->") > 0) method_rules++
          else class_or_other_rules++
          if (match(rule, /^[HSP]*L/)) {
            flags = substr(rule, 1, RLENGTH - 1)
            if (index(flags, "H")) hot_flag_rules++
            if (index(flags, "S")) startup_flag_rules++
            if (index(flags, "P")) post_startup_flag_rules++
          }
        }
        END {
          printf "  Nonblank rules: %d\n", total + 0
          printf "  Unique exact rules: %d\n", unique + 0
          printf "  Duplicate exact rules: %d\n", total - unique
          printf "  Method-rule candidates: %d\n", method_rules + 0
          printf "  Class/other-rule candidates: %d\n", class_or_other_rules + 0
          printf "  Rules carrying H/S/P flags: H=%d S=%d P=%d\n", hot_flag_rules + 0, startup_flag_rules + 0, post_startup_flag_rules + 0
        }
      ' "$profile_file"
      printf '\n'
    done
  } > "$profile_summary" || fail "Could not create the Baseline Profile artifact audit."
  [ "$profile_count" -ge 2 ] || fail "Profile audit expected both the Startup Profile and Home browse Baseline Profile artifacts; found $profile_count files."
  printf 'Generated profile artifact audit -> %s\n' "$profile_summary" >> "$RUN_DIR/copied-files.txt"
  cat "$profile_summary"
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
    require_recognizable_art_profile_rules "$profile_file"
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

count_json_pattern_occurrences() {
  awk -v pattern="$1" '
    { content = content $0 "\n" }
    END {
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
    function json_member_value(source, field,    source_length, object_depth, array_depth, in_string, escaped, key_start, position, character, key, colon_position, value_start, value_end, scan_position, scan_character, scan_in_string, scan_escaped, value_object_depth, value_array_depth, value, found_count, found_value) {
      sub(/^[[:space:]]*/, "", source)
      if (substr(source, 1, 1) != "{") return ""
      source_length = length(source)
      object_depth = 0
      array_depth = 0
      in_string = 0
      escaped = 0
      key_start = 0
      found_count = 0
      found_value = ""

      for (position = 1; position <= source_length; position++) {
        character = substr(source, position, 1)
        if (in_string) {
          if (escaped) escaped = 0
          else if (character == "\\") escaped = 1
          else if (character == "\"") {
            in_string = 0
            if (object_depth == 1 && array_depth == 0) {
              key = substr(source, key_start + 1, position - key_start - 1)
              colon_position = position + 1
              while (colon_position <= source_length && substr(source, colon_position, 1) ~ /[[:space:]]/) colon_position++
              if (substr(source, colon_position, 1) == ":" && key == field) {
                value_start = colon_position + 1
                while (value_start <= source_length && substr(source, value_start, 1) ~ /[[:space:]]/) value_start++
                if (value_start > source_length) return ""
                character = substr(source, value_start, 1)
                if (character == "\"") {
                  scan_in_string = 1
                  scan_escaped = 0
                  for (scan_position = value_start + 1; scan_position <= source_length; scan_position++) {
                    scan_character = substr(source, scan_position, 1)
                    if (scan_escaped) scan_escaped = 0
                    else if (scan_character == "\\") scan_escaped = 1
                    else if (scan_character == "\"") break
                  }
                  if (scan_position > source_length) return ""
                  value_end = scan_position
                } else if (character == "{" || character == "[") {
                  value_object_depth = 0
                  value_array_depth = 0
                  scan_in_string = 0
                  scan_escaped = 0
                  for (scan_position = value_start; scan_position <= source_length; scan_position++) {
                    scan_character = substr(source, scan_position, 1)
                    if (scan_in_string) {
                      if (scan_escaped) scan_escaped = 0
                      else if (scan_character == "\\") scan_escaped = 1
                      else if (scan_character == "\"") scan_in_string = 0
                    } else {
                      if (scan_character == "\"") scan_in_string = 1
                      else if (scan_character == "{") value_object_depth++
                      else if (scan_character == "}") {
                        value_object_depth--
                        if (value_object_depth == 0 && value_array_depth == 0) break
                      } else if (scan_character == "[") value_array_depth++
                      else if (scan_character == "]") {
                        value_array_depth--
                        if (value_object_depth == 0 && value_array_depth == 0) break
                      }
                    }
                  }
                  if (scan_position > source_length) return ""
                  value_end = scan_position
                } else {
                  value_end = value_start
                  while (value_end <= source_length) {
                    scan_character = substr(source, value_end, 1)
                    if (scan_character == "," || scan_character == "}") break
                    value_end++
                  }
                  value_end--
                  while (value_end >= value_start && substr(source, value_end, 1) ~ /[[:space:]]/) value_end--
                  if (value_end < value_start) return ""
                }
                value = substr(source, value_start, value_end - value_start + 1)
                found_count++
                if (found_count == 1) found_value = value
                else return ""
              }
            }
          }
        } else {
          if (character == "\"") {
            in_string = 1
            key_start = position
          } else if (character == "{") object_depth++
          else if (character == "}") object_depth--
          else if (character == "[") array_depth++
          else if (character == "]") array_depth--
        }
      }
      if (found_count != 1) return ""
      return found_value
    }

    function json_member_object(source, field, value) {
      value = json_member_value(source, field)
      if (substr(value, 1, 1) == "{") return value
      return ""
    }

    function count_matches(source, pattern,    rest, count) {
      rest = source
      count = 0
      while (match(rest, pattern)) {
        count++
        rest = substr(rest, RSTART + RLENGTH)
      }
      return count
    }

    function metric_field(object, field, value) {
      value = json_member_value(object, field)
      if (value ~ /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/) return value
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

      runs_value = json_member_value(object, "runs")
      if (runs_value == "") {
        print "FAIL: " metric_name " must contain exactly one direct per-iteration runs array; missing or duplicate members are not accepted."
        return 0
      }
      if (substr(runs_value, 1, 1) != "[" || substr(runs_value, length(runs_value), 1) != "]") {
        print "FAIL: " metric_name " runs value is not a complete array."
        return 0
      }
      runs_text = substr(runs_value, 2, length(runs_value) - 2)
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

      runs_value = json_member_value(object, "runs")
      if (runs_value == "") {
        print "FAIL: " metric_name " must contain exactly one direct per-iteration runs array; missing or duplicate members are not accepted."
        return 0
      }
      if (substr(runs_value, 1, 1) != "[" || substr(runs_value, length(runs_value), 1) != "]") {
        print "FAIL: " metric_name " runs value is not a complete array."
        return 0
      }
      runs_text = substr(runs_value, 2, length(runs_value) - 2)
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

      # AndroidX BenchmarkData places device metadata under the root context object.
      # Resolve each direct child explicitly; a sibling or nested decoy must not satisfy the API floor.
      reported_context = json_member_object(json, "context")
      reported_build = json_member_object(reported_context, "build")
      reported_version = json_member_object(reported_build, "version")
      reported_api = metric_field(reported_version, "sdk")
      if (reported_api == "" || reported_api !~ /^[0-9]+$/ || reported_api + 0 < 33) {
        print "FAIL: Macrobenchmark JSON must report context.build.version.sdk as Android API 33 or newer; top-level or unrelated metadata fields are ignored."
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
          metric_text = substr(metric_rest, metric_start, metric_length)
          metric_object_start = index(metric_text, "{")
          if (metric_object_start == 0) {
            print "FAIL: " metric_name " has no direct metric result object."
            exit 1
          }
          metric_object = substr(metric_text, metric_object_start)
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
          metric_text = substr(metric_rest, metric_start, metric_length)
          metric_object_start = index(metric_text, "{")
          if (metric_object_start == 0) {
            print "FAIL: " metric_name " has no direct metric result object."
            exit 1
          }
          metric_object = substr(metric_text, metric_object_start)
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
            metric_text = substr(metric_rest, metric_start, metric_length)
            metric_object_start = index(metric_text, "{")
            if (metric_object_start == 0) {
              print "FAIL: " metric_name " has no direct metric result object."
              exit 1
            }
            metric_object = substr(metric_text, metric_object_start)
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
    function json_member_value(source, field,    source_length, object_depth, array_depth, in_string, escaped, key_start, position, character, key, colon_position, value_start, value_end, scan_position, scan_character, scan_in_string, scan_escaped, value_object_depth, value_array_depth, value, found_count, found_value) {
      sub(/^[[:space:]]*/, "", source)
      if (substr(source, 1, 1) != "{") return ""
      source_length = length(source)
      object_depth = 0
      array_depth = 0
      in_string = 0
      escaped = 0
      key_start = 0
      found_count = 0
      found_value = ""

      for (position = 1; position <= source_length; position++) {
        character = substr(source, position, 1)
        if (in_string) {
          if (escaped) escaped = 0
          else if (character == "\\") escaped = 1
          else if (character == "\"") {
            in_string = 0
            if (object_depth == 1 && array_depth == 0) {
              key = substr(source, key_start + 1, position - key_start - 1)
              colon_position = position + 1
              while (colon_position <= source_length && substr(source, colon_position, 1) ~ /[[:space:]]/) colon_position++
              if (substr(source, colon_position, 1) == ":" && key == field) {
                value_start = colon_position + 1
                while (value_start <= source_length && substr(source, value_start, 1) ~ /[[:space:]]/) value_start++
                if (value_start > source_length) return ""
                character = substr(source, value_start, 1)
                if (character == "\"") {
                  scan_in_string = 1
                  scan_escaped = 0
                  for (scan_position = value_start + 1; scan_position <= source_length; scan_position++) {
                    scan_character = substr(source, scan_position, 1)
                    if (scan_escaped) scan_escaped = 0
                    else if (scan_character == "\\") scan_escaped = 1
                    else if (scan_character == "\"") break
                  }
                  if (scan_position > source_length) return ""
                  value_end = scan_position
                } else if (character == "{" || character == "[") {
                  value_object_depth = 0
                  value_array_depth = 0
                  scan_in_string = 0
                  scan_escaped = 0
                  for (scan_position = value_start; scan_position <= source_length; scan_position++) {
                    scan_character = substr(source, scan_position, 1)
                    if (scan_in_string) {
                      if (scan_escaped) scan_escaped = 0
                      else if (scan_character == "\\") scan_escaped = 1
                      else if (scan_character == "\"") scan_in_string = 0
                    } else {
                      if (scan_character == "\"") scan_in_string = 1
                      else if (scan_character == "{") value_object_depth++
                      else if (scan_character == "}") {
                        value_object_depth--
                        if (value_object_depth == 0 && value_array_depth == 0) break
                      } else if (scan_character == "[") value_array_depth++
                      else if (scan_character == "]") {
                        value_array_depth--
                        if (value_object_depth == 0 && value_array_depth == 0) break
                      }
                    }
                  }
                  if (scan_position > source_length) return ""
                  value_end = scan_position
                } else {
                  value_end = value_start
                  while (value_end <= source_length) {
                    scan_character = substr(source, value_end, 1)
                    if (scan_character == "," || scan_character == "}") break
                    value_end++
                  }
                  value_end--
                  while (value_end >= value_start && substr(source, value_end, 1) ~ /[[:space:]]/) value_end--
                  if (value_end < value_start) return ""
                }
                value = substr(source, value_start, value_end - value_start + 1)
                found_count++
                if (found_count == 1) found_value = value
                else return ""
              }
            }
          }
        } else {
          if (character == "\"") {
            in_string = 1
            key_start = position
          } else if (character == "{") object_depth++
          else if (character == "}") object_depth--
          else if (character == "[") array_depth++
          else if (character == "]") array_depth--
        }
      }
      if (found_count != 1) return ""
      return found_value
    }

    function json_member_object(source, field, value) {
      value = json_member_value(source, field)
      if (substr(value, 1, 1) == "{") return value
      return ""
    }

    function metric_field(object, field, value) {
      value = json_member_value(object, field)
      if (value ~ /^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/) return value
      return ""
    }

    function metric_string_field(object, field, value) {
      value = json_member_value(object, field)
      if (length(value) >= 2 && substr(value, 1, 1) == "\"" && substr(value, length(value), 1) == "\"") {
        return substr(value, 2, length(value) - 2)
      }
      return ""
    }

    function metric_boolean_field(object, field, value) {
      value = json_member_value(object, field)
      if (value == "true" || value == "false") return value
      return ""
    }

    function metric_runs(object, values,    runs_value, run_text) {
      runs_value = json_member_value(object, "runs")
      if (substr(runs_value, 1, 1) != "[" || substr(runs_value, length(runs_value), 1) != "]") return 0
      run_text = substr(runs_value, 2, length(runs_value) - 2)
      gsub(/[[:space:]]/, "", run_text)
      if (run_text == "") return 0
      return split(run_text, values, ",")
    }

    BEGIN {
      BOOTSTRAP_REPLICATES = 10000
      bootstrap_rng_state = 20261119
    }

    # Park-Miller PRNG: integer arithmetic stays below 2^53, so the sequence is
    # reproducible across the POSIX awk implementations used by host and phone.
    function bootstrap_random_index(count) {
      bootstrap_rng_state = (bootstrap_rng_state * 48271) % 2147483647
      return int((bootstrap_rng_state / 2147483647) * count) + 1
    }

    function sample_median(values, count,    ordered, i, j, current) {
      for (i = 1; i <= count; i++) ordered[i] = values[i]
      for (i = 2; i <= count; i++) {
        current = ordered[i]
        j = i - 1
        while (j > 0 && ordered[j] > current) {
          ordered[j + 1] = ordered[j]
          j--
        }
        ordered[j + 1] = current
      }
      if (count % 2 == 1) return ordered[(count + 1) / 2]
      return (ordered[count / 2] + ordered[count / 2 + 1]) / 2
    }

    function sort_numeric(values, left, right,    i, j, pivot, temporary) {
      i = left
      j = right
      pivot = values[int((left + right) / 2)]
      while (i <= j) {
        while (values[i] < pivot) i++
        while (values[j] > pivot) j--
        if (i <= j) {
          temporary = values[i]
          values[i] = values[j]
          values[j] = temporary
          i++
          j--
        }
      }
      if (left < j) sort_numeric(values, left, j)
      if (i < right) sort_numeric(values, i, right)
    }

    # Difference is right_arm median minus left_arm median. Each arm is
    # resampled independently because the existing JUnit arms are not paired.
    function bootstrap_median_difference(left_arm, right_arm, metric_index,    replicate, sample_index, random_index, left_sample, right_sample, differences, left_median, right_median, lower_index, upper_index) {
      for (replicate = 1; replicate <= BOOTSTRAP_REPLICATES; replicate++) {
        for (sample_index = 1; sample_index <= expected_iterations; sample_index++) {
          random_index = bootstrap_random_index(expected_iterations)
          if (metric_index == 1) {
            left_sample[sample_index] = ttid_runs[left_arm, random_index]
            right_sample[sample_index] = ttid_runs[right_arm, random_index]
          } else {
            left_sample[sample_index] = ttfd_runs[left_arm, random_index]
            right_sample[sample_index] = ttfd_runs[right_arm, random_index]
          }
        }
        left_median = sample_median(left_sample, expected_iterations)
        right_median = sample_median(right_sample, expected_iterations)
        differences[replicate] = right_median - left_median
      }
      sort_numeric(differences, 1, BOOTSTRAP_REPLICATES)
      lower_index = int((BOOTSTRAP_REPLICATES - 1) * 0.025) + 1
      upper_index = int((BOOTSTRAP_REPLICATES - 1) * 0.975) + 1
      bootstrap_ci_lower = differences[lower_index]
      bootstrap_ci_upper = differences[upper_index]
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

      # Keep every reported device field tied to the AndroidX root context object.
      context_object = json_member_object(json, "context")
      build_object = json_member_object(context_object, "build")
      version_object = json_member_object(build_object, "version")
      device_model = metric_string_field(build_object, "model")
      android_api = metric_field(version_object, "sdk")
      cpu_core_count = metric_field(context_object, "cpuCoreCount")
      cpu_max_frequency = metric_field(context_object, "cpuMaxFreqHz")
      cpu_locked = metric_boolean_field(context_object, "cpuLocked")
      memory_total = metric_field(context_object, "memTotalBytes")
      art_mainline_version = metric_field(context_object, "artMainlineVersion")
      sustained_performance = metric_boolean_field(context_object, "sustainedPerformanceModeEnabled")
      os_codename = metric_string_field(context_object, "osCodenameAbbreviated")
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
        sub(/^[[:space:]]*,[[:space:]]*/, "", result_rest)
        next_test_pattern = "\"name\"[[:space:]]*:[[:space:]]*\"[^\"]+\""
        if (match(result_rest, next_test_pattern)) result_object = "{" substr(result_rest, 1, RSTART - 1)
        else result_object = "{" result_rest
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
          metric_text = substr(result_object, RSTART, RLENGTH)
          metric_object_start = index(metric_text, "{")
          if (metric_object_start == 0) {
            print "FAIL: " metric_names[metric_index] " has no direct metric result object for " test_names[test_index] "."
            exit 1
          }
          metric_object = substr(metric_text, metric_object_start)
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
          metric_text = substr(result_object, RSTART, RLENGTH)
          metric_object_start = index(metric_text, "{")
          if (metric_object_start == 0) {
            print "FAIL: " metric_name " has no direct metric result object while writing ART summary."
            exit 1
          }
          metric_object = substr(metric_text, metric_object_start)
          art_median[test_index, art_index] = metric_field(metric_object, "median") + 0
        }
        class_load_count_pattern = "\"artClassLoadCount\"[[:space:]]*:"
        class_load_time_pattern = "\"artClassLoadSumMs\"[[:space:]]*:"
        if (match(result_object, class_load_count_pattern) && match(result_object, class_load_time_pattern)) {
          class_load_count_object_pattern = "\"artClassLoadCount\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          class_load_time_object_pattern = "\"artClassLoadSumMs\"[[:space:]]*:[[:space:]]*\\{[^}]*\\}"
          if (!match(result_object, class_load_count_object_pattern)) {
            print "FAIL: artClassLoadCount has no direct metric result object while writing ART summary."
            exit 1
          }
          class_load_count_text = substr(result_object, RSTART, RLENGTH)
          class_load_count_start = index(class_load_count_text, "{")
          if (class_load_count_start == 0) {
            print "FAIL: artClassLoadCount has no direct metric result object while writing ART summary."
            exit 1
          }
          class_load_count_object = substr(class_load_count_text, class_load_count_start)
          if (!match(result_object, class_load_time_object_pattern)) {
            print "FAIL: artClassLoadSumMs has no direct metric result object while writing ART summary."
            exit 1
          }
          class_load_time_text = substr(result_object, RSTART, RLENGTH)
          class_load_time_start = index(class_load_time_text, "{")
          if (class_load_time_start == 0) {
            print "FAIL: artClassLoadSumMs has no direct metric result object while writing ART summary."
            exit 1
          }
          class_load_time_object = substr(class_load_time_text, class_load_time_start)
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
      print "Exploratory bootstrap intervals (difference in medians; 10,000 resamples)"
      print "Difference is right-hand arm median minus left-hand arm median; each arm is resampled independently."
      for (metric_index = 1; metric_index <= 2; metric_index++) {
        metric = metric_names[metric_index]
        print metric
        bootstrap_median_difference(2, 3, metric_index)
        printf "  Incremental Baseline Profile effect (profile - partial without profile): %.1f ms; 95%% percentile interval [%.1f, %.1f] ms\n", median[test_names[3] SUBSEP metric] - median[test_names[2] SUBSEP metric], bootstrap_ci_lower, bootstrap_ci_upper
        bootstrap_median_difference(1, 3, metric_index)
        printf "  Total effect (profile - no compilation): %.1f ms; 95%% percentile interval [%.1f, %.1f] ms\n", median[test_names[3] SUBSEP metric] - median[test_names[1] SUBSEP metric], bootstrap_ci_lower, bootstrap_ci_upper
        print ""
      }

      print "Interpretation: descriptive only; ten iterations do not establish statistical significance."
      print "Bootstrap intervals quantify within-arm sampling uncertainty; they are not causal proof and do not correct systematic test-order, device-heating, or time-drift bias."
      print "The three JUnit arms execute as grouped test blocks, not an interleaved rotation. For decision-grade comparisons, repeat captures in rotated/interleaved arm order on the same phone and compare the combined evidence."
      print "Do not claim a speedup from the median difference or bootstrap interval alone. Inspect every per-iteration trace for skew, thermal throttling, and warm-up effects."
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

append_startup_readiness_timings() {
  execution_log="$1"
  awk '
    BEGIN {
      prefix = "INSTRUMENTATION_STATUS: stream=NUVIO_STARTUP_READINESS_TIMING: "
      journeys[1] = "startupNoCompilation"
      journeys[2] = "startupPartialWithoutBaselineProfile"
      journeys[3] = "startupWithBaselineProfile"
      for (journey_index = 1; journey_index <= 3; journey_index++) {
        expected_journey[journeys[journey_index]] = 1
      }
    }

    function reject(message) {
      printf "FAIL: Startup readiness timing records must contain exactly thirty valid per-launch samples: %s\n", message > "/dev/stderr"
      failed = 1
      exit 1
    }

    index($0, prefix) == 1 {
      payload = substr($0, length(prefix) + 1)
      token_count = split(payload, tokens, /[[:space:]]+/)
      if (token_count != 8) {
        reject("readiness timing record must contain journey, iteration, four stage durations, total duration and outcome.")
      }

      journey_token = tokens[1]
      iteration_token = tokens[2]
      active_token = tokens[3]
      home_token = tokens[4]
      main_content_token = tokens[5]
      fully_drawn_token = tokens[6]
      total_token = tokens[7]
      outcome_token = tokens[8]

      if (journey_token !~ /^journey=[A-Za-z0-9_]+$/ ||
          iteration_token !~ /^iteration=[0-9]+$/ ||
          active_token !~ /^active_window_wait_ms=[0-9]+$/ ||
          home_token !~ /^home_marker_wait_ms=[0-9]+$/ ||
          main_content_token !~ /^main_content_marker_wait_ms=[0-9]+$/ ||
          fully_drawn_token !~ /^fully_drawn_marker_wait_ms=[0-9]+$/ ||
          total_token !~ /^total_wait_ms=[0-9]+$/ ||
          outcome_token !~ /^outcome=[a-z_]+$/) {
        reject("readiness timing record contains a missing or malformed field.")
      }

      sub(/^journey=/, "", journey_token)
      sub(/^iteration=/, "", iteration_token)
      sub(/^active_window_wait_ms=/, "", active_token)
      sub(/^home_marker_wait_ms=/, "", home_token)
      sub(/^main_content_marker_wait_ms=/, "", main_content_token)
      sub(/^fully_drawn_marker_wait_ms=/, "", fully_drawn_token)
      sub(/^total_wait_ms=/, "", total_token)
      sub(/^outcome=/, "", outcome_token)

      iteration = iteration_token + 0
      active_wait = active_token + 0
      home_wait = home_token + 0
      main_content_wait = main_content_token + 0
      fully_drawn_wait = fully_drawn_token + 0
      total_wait = total_token + 0

      if (!(journey_token in expected_journey)) {
        reject("readiness timing record names an unexpected startup comparison journey: " journey_token)
      }
      if (iteration < 0 || iteration > 9) {
        reject("readiness timing iteration must be between 0 and 9 for " journey_token ".")
      }
      # Active-window + Home waits share the 60s launch deadline; both post-Home
      # marker waits share one 15s deadline. Each final poll may add at most 250ms.
      if (active_wait > 15250 || active_wait + home_wait > 60250 ||
          main_content_wait + fully_drawn_wait > 15250 || total_wait > 75500 ||
          total_wait < active_wait + home_wait + main_content_wait + fully_drawn_wait) {
        reject("readiness timing durations are outside the configured timeout budgets or inconsistent.")
      }
      if (outcome_token != "ready") {
        reject("successful Macrobenchmark output contains a non-ready startup observation for " journey_token ".")
      }

      key = journey_token SUBSEP iteration
      if (key in seen) {
        reject("duplicate readiness timing record for " journey_token " iteration " iteration ".")
      }
      seen[key] = 1
      case_count[journey_token]++
      record_count++
      records[record_count] = payload
    }

    END {
      if (failed) exit 1
      if (record_count != 30) {
        printf "FAIL: Startup readiness timing records must contain exactly thirty valid per-launch samples; found %d.\n", record_count > "/dev/stderr"
        exit 1
      }
      for (journey_index = 1; journey_index <= 3; journey_index++) {
        journey = journeys[journey_index]
        if (case_count[journey] != 10) {
          printf "FAIL: Startup readiness timing records must contain ten samples for %s; found %d.\n", journey, case_count[journey] + 0 > "/dev/stderr"
          exit 1
        }
        for (iteration = 0; iteration < 10; iteration++) {
          if (!((journey SUBSEP iteration) in seen)) {
            printf "FAIL: Startup readiness timing records are missing %s iteration %d.\n", journey, iteration > "/dev/stderr"
            exit 1
          }
        }
      }

      print ""
      print "UIAutomator readiness observations after startActivityAndWait"
      print "Each row measures only the test-side waits for active-window ownership, Home marker, MainAppContent-ready marker and Activity fully-drawn marker after Macrobenchmark returned from startActivityAndWait."
      print "These stage timings are diagnostic only; they are not TTID, TTFD, or total launch time."
      for (record_index = 1; record_index <= record_count; record_index++) {
        printf "  NUVIO_STARTUP_READINESS_TIMING: %s\n", records[record_index]
      }
    }
  ' "$execution_log" >> "$RUN_DIR/benchmark-summary.txt" || fail "Could not validate and append the thirty per-launch startup readiness timing records from instrumentation.log."
}

require_memory_profile_result_data() {
  json_files=0
  result_count=0
  summary_result_file=""

  for result_file in "$RUN_DIR"/*benchmarkData.json; do
    [ -f "$result_file" ] || continue
    json_files=$((json_files + 1))
    [ -s "$result_file" ] || fail "Memory-profile result is empty: $result_file."
    [ -n "$summary_result_file" ] || summary_result_file="$result_file"
    result_count=$((result_count + $(count_json_named_results startupWithBaselineProfileMemoryProfile "$result_file")))
  done

  [ "$json_files" -eq 1 ] || fail "Memory-profile result must contain exactly one benchmarkData.json file; found $json_files."
  [ "$result_count" -eq 1 ] || fail "Memory-profile result must contain exactly one startupWithBaselineProfileMemoryProfile result; found $result_count."
  class_result_count="$(count_json_pattern_occurrences '"className"[[:space:]]*:[[:space:]]*"com[.]nuvio[.]android[.]baselineprofile[.]StartupMemoryBenchmark"' "$summary_result_file")"
  repeat_iterations_count="$(count_json_pattern_occurrences '"repeatIterations"[[:space:]]*:[[:space:]]*1' "$summary_result_file")"
  [ "$class_result_count" -eq 1 ] && [ "$repeat_iterations_count" -eq 1 ] ||
    fail "Memory-profile result must identify StartupMemoryBenchmark and exactly one ordinary measurement iteration (class=$class_result_count, repeatIterations=$repeat_iterations_count)."
  grep -Fq 'INSTRUMENTATION_STATUS: class=com.nuvio.android.baselineprofile.StartupMemoryBenchmark' "$RUN_DIR/instrumentation.log" ||
    fail "Memory-profile result did not come from the dedicated StartupMemoryBenchmark class."
  grep -Fq 'INSTRUMENTATION_STATUS: test=startupWithBaselineProfileMemoryProfile' "$RUN_DIR/instrumentation.log" ||
    fail "Memory-profile result did not execute startupWithBaselineProfileMemoryProfile."

  memory_label_count="$(count_json_pattern_occurrences '"label"[[:space:]]*:[[:space:]]*"Memory Profiling Iteration 0"' "$summary_result_file")"
  ordinary_label_count="$(count_json_pattern_occurrences '"label"[[:space:]]*:[[:space:]]*"Trace Iteration 0"' "$summary_result_file")"
  total_output_labels="$(count_json_pattern_occurrences '"label"[[:space:]]*:' "$summary_result_file")"
  [ "$memory_label_count" -eq 1 ] && [ "$ordinary_label_count" -eq 1 ] && [ "$total_output_labels" -eq 2 ] ||
    fail "Memory-profile result must contain exactly one ordinary Trace Iteration 0 and one Memory Profiling Iteration 0 output object; got memory=$memory_label_count ordinary=$ordinary_label_count total=$total_output_labels."

  memory_output_object="$(awk '
    { json = json $0 "\n" }
    END {
      pattern = "\\{[^{}]*\"label\"[[:space:]]*:[[:space:]]*\"Memory Profiling Iteration 0\"[^{}]*\\}"
      if (!match(json, pattern)) exit 1
      printf "%s", substr(json, RSTART, RLENGTH)
    }
  ' "$summary_result_file")" || fail "Memory-profile result has no complete Memory Profiling Iteration 0 output object."
  printf '%s\n' "$memory_output_object" | grep -Eq '"type"[[:space:]]*:[[:space:]]*"PerfettoTrace"' ||
    fail "Memory-profile result must report the memory output as a PerfettoTrace."
  memory_trace_filename="$(printf '%s\n' "$memory_output_object" | awk '
    {
      if (match($0, /"filename"[[:space:]]*:[[:space:]]*"[^"]*"/)) {
        value = substr($0, RSTART, RLENGTH)
        sub(/^[^:]*:[[:space:]]*"/, "", value)
        sub(/"$/, "", value)
        print value
        exit
      }
    }
  ')"
  [ -n "$memory_trace_filename" ] || fail "Memory-profile result has no profiler filename."
  case "$memory_trace_filename" in
    *-memoryProfiling.perfetto-trace) ;;
    *) fail "Memory-profile result filename must end in -memoryProfiling.perfetto-trace; got '$memory_trace_filename'." ;;
  esac
  memory_trace_basename="$(basename "$memory_trace_filename")"
  memory_trace_file="$RUN_DIR/$memory_trace_basename"
  [ -s "$memory_trace_file" ] || fail "Memory-profile result trace is missing or empty: $memory_trace_basename."
  copied_memory_trace_count="$(find "$RUN_DIR" -maxdepth 1 -type f -name '*-memoryProfiling.perfetto-trace' -print | wc -l | tr -d ' ')"
  [ "$copied_memory_trace_count" -eq 1 ] || fail "Expected exactly one copied non-empty memory-profile trace; found $copied_memory_trace_count."

  {
    printf 'Nuvio Android startup heap allocation diagnostic\n'
    printf 'Target app: %s\n' "$APP_PACKAGE"
    printf 'Instrumentation: %s\n' "$INSTRUMENTATION"
    printf 'Test: startupWithBaselineProfileMemoryProfile\n'
    printf 'Compilation mode: CompilationMode.Partial(BaselineProfileMode.Require)\n'
    printf 'Startup mode: COLD\n'
    printf 'Ordinary measured launches: one unprofiled iteration (diagnostic context only)\n'
    printf 'Additional memory profiling launches: one dedicated AndroidX profiling pass\n'
    printf 'ART heap sampling: enabled (com.android.art)\n'
    printf 'Native heap sampling: enabled (libc.malloc)\n'
    printf 'Profiler: Perfetto heapprofd\n'
    printf 'Allocation sampling interval: 2048 bytes\n'
    printf 'Continuous dump interval: 500 ms\n'
    printf 'Shared-memory buffer: 8388608 bytes\n'
    printf 'Allocation evidence: sampled call stacks, not exact heap totals or RSS/PSS\n'
    printf 'Heapprofd behavior: block_client=true; completeness prioritized over frame timing\n'
    printf 'Startup coverage caveat: some earliest Zygote-specialization allocations may precede profiling\n'
    printf 'Memory profiler output: Memory Profiling Iteration 0\n'
    printf 'Memory profile trace: %s\n' "$memory_trace_basename"
    printf '\nThis is an allocation diagnostic, not a statistical startup comparison. It is not a Java heap-retention graph or a complete ledger of every allocation. Do not use its single TTID/TTFD sample to claim a startup improvement. The normal timing suite remains unprofiled.\n'
    printf '\nInspect the trace in Perfetto UI (https://ui.perfetto.dev/) or Android Performance Analyzer. The trace embeds pprof heap-profile data; Perfetto traceconv can export pprof data for offline analysis.\n'
    printf '\nRun provenance\n'
    cat "$RUN_DIR/provenance.txt"
    printf '\nThermal integrity\n'
    cat "$RUN_DIR/thermal-integrity.txt"
    printf '\nBattery Saver integrity\n'
    cat "$RUN_DIR/battery-saver-integrity.txt"
  } > "$RUN_DIR/memory-profile-summary.txt" || fail "Could not write the memory-profile summary."
  printf 'Generated thermal integrity -> %s\n' "$RUN_DIR/thermal-integrity.txt" >> "$RUN_DIR/copied-files.txt"
  printf 'Generated Battery Saver integrity -> %s\n' "$RUN_DIR/battery-saver-integrity.txt" >> "$RUN_DIR/copied-files.txt"
  printf 'Generated memory-profile summary -> %s\n' "$RUN_DIR/memory-profile-summary.txt" >> "$RUN_DIR/copied-files.txt"
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
  append_startup_readiness_timings "$RUN_DIR/instrumentation.log"
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

capture_profile_flush_diagnostics() {
  diagnostics_file="$RUN_DIR/profile-flush-diagnostics.txt"
  {
    printf 'Capture: after instrumentation failure\n'
    printf 'Timestamp: '
    date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'unavailable\n'

    printf '\nTarget package processes after instrumentation:\n'
    if command -v ps >/dev/null 2>&1; then
      capture_target_process_snapshot
    else
      printf 'ps unavailable\n'
    fi

    printf '\nAndroidX-equivalent process lookup:\n'
    capture_androidx_process_snapshot

    printf '\nProfileInstaller receiver in installed package metadata:\n'
    if command -v dumpsys >/dev/null 2>&1; then
      dumpsys package "$APP_PACKAGE" 2>&1 \
        | grep -C 2 -F 'androidx.profileinstaller.ProfileInstallReceiver' \
        || printf 'ProfileInstallReceiver was not found in dumpsys package output.\n'
    else
      printf 'dumpsys unavailable\n'
    fi

    printf '\nProfileInstaller and AndroidRuntime logcat entries:\n'
    if command -v logcat >/dev/null 2>&1; then
      logcat -d -v threadtime -s 'ProfileInstaller:*' 'Benchmark:*' 'art:*' 'AndroidRuntime:E' 2>&1 \
        || printf 'Filtered logcat capture failed.\n'
    else
      printf 'logcat unavailable\n'
    fi

    printf '\nInterpretation: this is a post-run diagnostic snapshot, not proof of process state at the exact profile-flush instant. Correlate it with instrumentation.log and foreground snapshots.\n'
  } > "$diagnostics_file" 2>&1 || {
    printf 'Could not write profile-flush diagnostics: %s\n' "$diagnostics_file" >&2
    return 0
  }
  printf 'Saved failure diagnostics -> %s\n' "$diagnostics_file"
}

verify_profile_installer_receiver() {
  # Verify the installed merged-manifest/package metadata exposes the receiver
  # that AndroidX addresses during its own per-process collection flush.
  # A standalone SAVE_PROFILE broadcast here is not a valid substitute for
  # observing AndroidX's later process discovery and flush.
  receiver="androidx.profileinstaller.ProfileInstallReceiver"
  receiver_state="$(dumpsys package "$APP_PACKAGE" 2>&1 | grep -C 2 -F "$receiver" || true)"
  {
    printf 'Target package: %s\n' "$APP_PACKAGE"
    printf 'Expected receiver: %s\n' "$receiver"
    printf 'Installed package metadata evidence:\n'
    if [ -n "$receiver_state" ]; then
      printf '%s\n' "$receiver_state"
    else
      printf 'Receiver not present in installed package metadata.\n'
    fi
  } > "$RUN_DIR/profile-installer-receiver.txt"
  [ -n "$receiver_state" ] || fail "Installed benchmark APK does not expose $receiver in package metadata. Check the merged manifest/R8 output and rebuild the matching APK pair. See $RUN_DIR/profile-installer-receiver.txt."
}

capture_target_process_snapshot() {
  # Record exact, colon-child and dotted-prefix candidates. Dotted names may
  # belong to a separately installed package; this is a raw snapshot, not ownership proof.
  if ! command -v ps >/dev/null 2>&1; then
    printf 'ps unavailable; independent process snapshot could not be captured.\n'
    return 0
  fi
  ps -A 2>&1 | awk -v package="$APP_PACKAGE" '
    $NF == package || index($NF, package ":") == 1 || index($NF, package ".") == 1 {
      print
      found = 1
    }
    END {
      if (!found) print "No target package process listed in this snapshot."
    }
  '
}

androidx_is_subpackage_installed() {
  # Approximate Shell.isSubpackageInstalled(): strip the process suffix, then check
  # every dotted package prefix. AndroidX uses the instrumentation context's
  # PackageManager; pm path runs as shell, so package-visibility results may differ.
  process_name="$1"
  base_process_name="${process_name%%:*}"
  case "$base_process_name" in
    "$APP_PACKAGE".*) ;;
    *) return 1 ;;
  esac

  remaining="${base_process_name#"$APP_PACKAGE".}"
  candidate="$APP_PACKAGE"
  while :; do
    case "$remaining" in
      *.*)
        component="${remaining%%.*}"
        remaining="${remaining#*.}"
        candidate="$candidate.$component"
        ;;
      *)
        candidate="$candidate.$remaining"
        if pm path "$candidate" >/dev/null 2>&1; then
          return 0
        fi
        break
        ;;
    esac

    if pm path "$candidate" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

capture_androidx_process_snapshot() {
  # Reproduce AndroidX Shell.pgrepLF flags exactly for the device API level.
  printf 'AndroidX-equivalent process lookup: '
  if [ "$api" -ge 36 ]; then
    printf "pgrep -l -f -a '%s'\n" "$ANDROIDX_PROCESS_PATTERN"
    if pgrep_output="$(pgrep -l -f -a "$ANDROIDX_PROCESS_PATTERN" 2>&1)"; then
      pgrep_status=0
    else
      pgrep_status=$?
    fi
  else
    printf "pgrep -l -f '%s'\n" "$ANDROIDX_PROCESS_PATTERN"
    if pgrep_output="$(pgrep -l -f "$ANDROIDX_PROCESS_PATTERN" 2>&1)"; then
      pgrep_status=0
    else
      pgrep_status=$?
    fi
  fi

  if [ "$pgrep_status" -eq 0 ]; then
    if [ -n "$pgrep_output" ]; then
      printf 'Raw pgrep output:\n%s\n' "$pgrep_output"
      printf 'Process-name classification (exact package/colon; dotted prefix uses shell pm path approximation):\n'
      printf 'Dotted-child package-prefix checks use shell pm path and are advisory only.\n'
      printf '%s\n' "$pgrep_output" | while read -r process_pid process_name remainder; do
        case "$process_pid" in
          ''|*[!0-9]*)
            printf 'MALFORMED pgrep row: %s\n' "$process_pid $process_name $remainder"
            ;;
          *)
            case "$process_name" in
              "$APP_PACKAGE"|"$APP_PACKAGE":*)
                printf 'ANDROIDX_ACCEPTED pid=%s processName=%s\n' "$process_pid" "$process_name"
                ;;
              "$APP_PACKAGE".*)
                if androidx_is_subpackage_installed "$process_name"; then
                  printf 'SHELL_APPROX_REJECTED_INSTALLED_SUBPACKAGE pid=%s processName=%s\n' "$process_pid" "$process_name"
                else
                  printf 'SHELL_APPROX_ACCEPTED_UNINSTALLED_DOTTED_CHILD pid=%s processName=%s\n' "$process_pid" "$process_name"
                fi
                ;;
              *)
                printf 'ANDROIDX_REJECTED pid=%s processName=%s\n' "$process_pid" "$process_name"
                ;;
            esac
            ;;
        esac
      done
    else
      printf 'pgrep exited successfully but returned no process rows.\n'
    fi
  elif [ "$pgrep_status" -eq 1 ]; then
    printf 'No matching process (pgrep exit status 1).\n'
  else
    printf 'pgrep command failed (exit status %s).\n' "$pgrep_status"
    if [ -n "$pgrep_output" ]; then
      printf 'pgrep error output:\n%s\n' "$pgrep_output"
    else
      printf 'pgrep returned no error output.\n'
    fi
  fi
  printf 'pgrep exit status: %s\n' "$pgrep_status"
}

start_profile_collection_diagnostics() {
  PROFILE_PROCESS_MONITOR_PID=""
  PROFILE_LOGCAT_MONITOR_PID=""
  process_timeline="$RUN_DIR/profile-process-timeline.txt"
  logcat_timeline="$RUN_DIR/profile-collection-logcat.txt"

  {
    printf 'Live target-process samples during BaselineProfile instrumentation\n'
    printf 'Target package: %s\n' "$APP_PACKAGE"
    printf 'Sampling interval: approximately 1 second\n'
    printf "AndroidX process pattern: %s\n" "$ANDROIDX_PROCESS_PATTERN"
    printf 'Each sample records both ps -A and the API-correct AndroidX pgrep lookup.\n'
    printf 'Logcat uses threadtime format for PID/TID and includes ART, Benchmark and ProfileInstaller tags for per-process profile-save correlation.\n'
    printf 'A missing sample is not proof that no process existed between samples.\n'
  } > "$process_timeline"

  {
    printf 'Initial process snapshot immediately before instrumentation: '
    date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'timestamp unavailable\n'
    printf '\nIndependent ps -A snapshot:\n'
    capture_target_process_snapshot
    printf '\n'
    capture_androidx_process_snapshot
  } >> "$process_timeline" 2>&1

  (
    while :; do
      printf '\nSample: '
      date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'timestamp unavailable\n'
      printf '\nIndependent ps -A snapshot:\n'
      capture_target_process_snapshot
      printf '\n'
      capture_androidx_process_snapshot
      sleep 1
    done
  ) >> "$process_timeline" 2>&1 &
  PROFILE_PROCESS_MONITOR_PID=$!

  if command -v logcat >/dev/null 2>&1; then
    logcat -v threadtime -s 'Benchmark:*' 'ProfileInstaller:*' 'art:*' 'AndroidRuntime:E' \
      >> "$logcat_timeline" 2>&1 &
    PROFILE_LOGCAT_MONITOR_PID=$!
  else
    printf 'logcat unavailable; AndroidX live log capture was not started.\n' > "$logcat_timeline"
  fi
}
stop_profile_collection_diagnostics() {
  if [ -n "${PROFILE_PROCESS_MONITOR_PID:-}" ]; then
    kill "$PROFILE_PROCESS_MONITOR_PID" 2>/dev/null || true
    wait "$PROFILE_PROCESS_MONITOR_PID" 2>/dev/null || true
    PROFILE_PROCESS_MONITOR_PID=""
  fi
  if [ -n "${PROFILE_LOGCAT_MONITOR_PID:-}" ]; then
    kill "$PROFILE_LOGCAT_MONITOR_PID" 2>/dev/null || true
    wait "$PROFILE_LOGCAT_MONITOR_PID" 2>/dev/null || true
    PROFILE_LOGCAT_MONITOR_PID=""
  fi
  if command -v logcat >/dev/null 2>&1; then
    printf '\nPost-run AndroidX logcat snapshot (may repeat live entries):\n' >> "$RUN_DIR/profile-collection-logcat.txt"
    logcat -d -v threadtime -s 'Benchmark:*' 'ProfileInstaller:*' 'art:*' 'AndroidRuntime:E' \
      >> "$RUN_DIR/profile-collection-logcat.txt" 2>&1 || true
  fi

  {
    printf '\nFinal process snapshot after instrumentation: '
    date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'timestamp unavailable\n'
    printf '\nIndependent ps -A snapshot:\n'
    capture_target_process_snapshot
    printf '\n'
    capture_androidx_process_snapshot
  } >> "$RUN_DIR/profile-process-timeline.txt" 2>&1
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
  if [ "$rule" = BaselineProfile ]; then
    verify_profile_installer_receiver
    start_profile_collection_diagnostics
  fi
  printf 'Running %s on %s. Keep the phone cool, plugged in, and otherwise idle.\n' "$rule" "$FLAVOR"
  printf 'Do not reopen LADB or run shell commands until instrumentation finishes. A foreground LADB/System UI window can invalidate the app-readiness check or interrupt profile collection.\n'
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
  if [ "$rule" = BaselineProfile ]; then
    stop_profile_collection_diagnostics
  fi
  set -e
  capture_foreground_state after
  if [ "$rule" = macrobenchmark ]; then
    capture_device_environment after
    THERMAL_STATUS_AFTER="$(read_thermal_status_from_snapshot "$RUN_DIR/environment-after.txt")"
    BATTERY_SAVER_AFTER="$(read_battery_saver_from_snapshot "$RUN_DIR/environment-after.txt")"
    write_thermal_integrity_summary "$THERMAL_STATUS_BEFORE" "$THERMAL_STATUS_AFTER"
    write_battery_saver_integrity_summary "$BATTERY_SAVER_BEFORE" "$BATTERY_SAVER_AFTER"
  fi
  if [ "$result" -ne 0 ] || grep -Eiq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]' "$RUN_DIR/instrumentation.log"; then
    capture_profile_flush_diagnostics
    cat "$RUN_DIR/instrumentation.log"
    if [ "$rule" = BaselineProfile ]; then
      fail "Instrumentation failed (exit code $result). See $RUN_DIR/instrumentation.log, foreground-before.txt, foreground-after.txt, profile-installer-receiver.txt, profile-process-timeline.txt, profile-collection-logcat.txt and profile-flush-diagnostics.txt. These captures help separate a marker timeout, an external foreground interruption, missing target-process discovery and an ART profile-save failure."
    fi
    fail "Instrumentation failed (exit code $result). See $RUN_DIR/instrumentation.log, foreground-before.txt, foreground-after.txt and profile-flush-diagnostics.txt. These captures help separate a marker timeout, an external foreground interruption and an ART profile-save failure."
  fi
  cat "$RUN_DIR/instrumentation.log"
  case "$rule" in
    BaselineProfile)
      copy_recent_outputs profile
      printf 'Installed receiver evidence -> %s\n' "$RUN_DIR/profile-installer-receiver.txt" >> "$RUN_DIR/copied-files.txt"
      printf 'Live target-process timeline -> %s\n' "$RUN_DIR/profile-process-timeline.txt" >> "$RUN_DIR/copied-files.txt"
      printf 'Live AndroidX/ProfileInstaller logcat -> %s\n' "$RUN_DIR/profile-collection-logcat.txt" >> "$RUN_DIR/copied-files.txt"
      require_non_empty_startup_profile
      require_non_empty_home_browse_profile
      write_profile_audit_summary
      ;;
    macrobenchmark)
      copy_recent_outputs benchmark
      if [ "$ACTION" = memory-profile ]; then
        require_memory_profile_result_data
        cat "$RUN_DIR/memory-profile-summary.txt"
      else
        require_macrobenchmark_cases
        require_macrobenchmark_result_data
        printf 'Generated benchmark summary -> %s\n' "$RUN_DIR/benchmark-summary.txt" >> "$RUN_DIR/copied-files.txt"
        cat "$RUN_DIR/benchmark-summary.txt"
      fi
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
    printf '\nThe three cold-start comparison arms are: no compilation; partial compilation without a Baseline Profile (3 warmups); and partial compilation requiring the packaged Baseline Profile. Compare TTID and TTFD medians and per-iteration traces. The profile-enabled arm uses the Baseline Profile already packaged in this benchmarkRelease APK; text rules collected by the separate collect step are not installed into this APK.\n'
    ;;
  memory-profile)
    run_instrumentation macrobenchmark "$MEMORY_BENCHMARK_CLASS"
    printf '\nMemory profiling completed as a separate diagnostic pass. Open the copied memory-profile trace in Perfetto UI or Android Performance Analyzer; do not treat its single timing sample as a startup comparison.\n'
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
  *) usage; fail "Unknown action '$ACTION'. Use install-profile, run, collect, install, measure, memory-profile or help." ;;
esac
