#!/system/bin/sh
# Phone-only Nuvio startup benchmark runner for LADB / Android shell.
# Keep this script and all eight APKs in the same Downloads folder.
set -eu

APP_PACKAGE="com.nuvio.media.benchmark"
GENERATOR_CLASS="com.nuvio.android.baselineprofile.BaselineProfileGenerator"
BENCHMARK_CLASS="com.nuvio.android.baselineprofile.StartupBenchmark"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OUTPUT_ROOT="${NUVIO_PHONE_OUTPUT_DIR:-/sdcard/Download/NuvioStartupBenchmark}"
MEDIA_ROOT="${NUVIO_PHONE_MEDIA_ROOT:-/sdcard/Android/media}"

fail() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'HELP'
Nuvio Android phone-only startup benchmark

Keep all eight APKs and this script together:
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
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh collect full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh measure full
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install-profile playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh collect playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh install playstore
  sh /sdcard/Download/NuvioBenchmark/nuvio-phone-benchmark.sh measure playstore

Results are copied to /sdcard/Download/NuvioStartupBenchmark.
The benchmark app uses com.nuvio.media.benchmark; production Nuvio is untouched.
HELP
}

check_device() {
  command -v pm >/dev/null 2>&1 || fail "Run this script from LADB or another Android ADB shell, not a regular terminal."
  api="$(getprop ro.build.version.sdk | tr -d '\r')"
  case "$api" in ''|*[!0-9]*) fail "Cannot determine Android API level." ;; esac
  [ "$api" -ge 33 ] || fail "Baseline Profile collection without root requires Android 13 / API 33 or later. This device reports API $api."
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
  INSTRUMENTATION="$(pm list instrumentation 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *"(target=$APP_PACKAGE)"*) printf '%s\n' "$line" | sed 's/^instrumentation://; s/[[:space:]].*//' ;;
    esac
  done | head -n 1)"
  [ -n "$INSTRUMENTATION" ] || fail "The matching test APK is not installed or does not target $APP_PACKAGE. Run install-profile or install for this flavor first."
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

install_variant() {
  apk_paths
  [ -f "$APP_APK" ] || fail "Missing app APK: $APP_APK. Extract all eight APKs and this script into one folder."
  [ -f "$TEST_APK" ] || fail "Missing test APK: $TEST_APK. Extract all eight APKs and this script into one folder."
  if [ "$ACTION" = install-profile ]; then
    variant_label=nonMinifiedRelease
  else
    variant_label=benchmarkRelease
  fi
  printf 'Installing %s %s app without touching production Nuvio...\n' "$FLAVOR" "$variant_label"
  pm install -r "$APP_APK" || fail "App installation failed. Do not uninstall production Nuvio. If an older .benchmark build has a different signing key, save results and remove only com.nuvio.media.benchmark before retrying."
  pm install -t -r "$TEST_APK" || fail "Instrumentation APK installation failed. Keep the benchmark app installed and check available storage."
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
    BaselineProfile) copy_recent_outputs profile ;;
    macrobenchmark) copy_recent_outputs benchmark ;;
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
  *) usage; fail "Unknown action '$ACTION'. Use install-profile, collect, install, measure or help." ;;
esac
