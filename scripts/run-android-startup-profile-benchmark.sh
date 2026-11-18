#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
BENCHMARK_PACKAGE="com.nuvio.media.benchmark"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || fail "Android Debug Bridge (adb) is not installed or not on PATH."
command -v unzip >/dev/null 2>&1 || fail "unzip is required to inspect the packaged Baseline Profile."
[[ -x ./gradlew ]] || fail "Run this script from a checkout containing the executable Gradle wrapper."
[[ -f local.properties ]] || fail "local.properties is missing; configure NUVIO_SUPABASE_URL and NUVIO_SUPABASE_ANON_KEY."
for key in NUVIO_SUPABASE_URL NUVIO_SUPABASE_ANON_KEY; do
  grep -Eq "^[[:space:]]*${key}[[:space:]]*=[[:space:]]*.+$" local.properties || fail "local.properties is missing ${key}."
done

mapfile -t connected_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[[ "${#connected_devices[@]}" -eq 1 ]] || fail "Connect exactly one authorized physical Android device. Found ${#connected_devices[@]} ready devices."
DEVICE_SERIAL="${connected_devices[0]}"
API_LEVEL="$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_MODEL="$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.manufacturer | tr -d '\r') $(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model | tr -d '\r')"
DEVICE_ABI="$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
KERNEL_QEMU="$(adb -s "$DEVICE_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
HARDWARE="$(adb -s "$DEVICE_SERIAL" shell getprop ro.hardware | tr -d '\r')"
[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || fail "Could not determine Android API level."
[[ "$DEVICE_ABI" == "arm64-v8a" ]] || fail "This phone-only benchmark bundle contains arm64-v8a APKs. Connected device ABI is '$DEVICE_ABI'."
[[ "$KERNEL_QEMU" != "1" && "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" && "$HARDWARE" != "qemu" ]] || fail "A physical device is required."
if (( API_LEVEL <= 33 )); then
  # The combined desktop workflow always performs authenticated Home measurement.
  # Match AndroidX's instrumentation-shell capability probe before its compilation
  # reset can uninstall/reinstall the app and erase sign-in data on API 33.
  ROOT_ID="$(adb -s "$DEVICE_SERIAL" shell id 2>/dev/null | tr -d '\r' || true)"
  if [[ "$ROOT_ID" != *"uid=0"* ]]; then
    ROOT_ID="$(adb -s "$DEVICE_SERIAL" shell su root id 2>/dev/null | tr -d '\r' || true)"
  fi
  [[ "$ROOT_ID" == *"uid=0"* ]] || fail "Authenticated Home measurement requires API 34+ or a rooted API 28-33 device. AndroidX Macrobenchmark reinstalls the target app on unrooted pre-34 devices and clears sign-in data. Device API: $API_LEVEL."
fi

TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
OUTPUT_DIR="${NUVIO_STARTUP_BENCHMARK_OUTPUT:-$ROOT_DIR/build/android-startup-profile-benchmark/$TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"
cat > "$OUTPUT_DIR/device.txt" <<META
Device: $DEVICE_MODEL
Android API: $API_LEVEL
Run (UTC): $TIMESTAMP
Device serial: redacted
App package: $BENCHMARK_PACKAGE
Flavors: full, playstore
Startup state: signed-in Home; enforced by nuvio_startup_ready
Startup benchmark: 10 cold iterations per compilation mode and flavor
META

run_gradle_step() {
  local label="$1"; shift
  printf '\n========== %s ==========\n' "$label"
  if ./gradlew --no-daemon --console=plain "$@" 2>&1 | tee "$OUTPUT_DIR/$label.log"; then
    return 0
  else
    local status=$?
    printf 'Gradle step %s failed (exit %s). See %s/%s.log\n' "$label" "$status" "$OUTPUT_DIR" "$label" >&2
    return "$status"
  fi
}

require_startup_metrics() {
  local label="$1"
  local log_file="$OUTPUT_DIR/$label.log"
  [[ -s "$log_file" ]] || fail "$label did not produce a Macrobenchmark log."

  for metric in timeToInitialDisplayMs timeToFullDisplayMs; do
    grep -Fq "$metric" "$log_file" || \
      fail "$label did not emit $metric; check that its JUnit benchmark cases ran and that the app reported fully drawn."
  done
}

printf 'Physical device: %s (API %s)\n' "$DEVICE_MODEL" "$API_LEVEL"
printf 'Benchmark package: %s (production com.nuvio.media remains installed)\n' "$BENCHMARK_PACKAGE"
printf 'Logs and results: %s\n' "$OUTPUT_DIR"
printf 'The benchmark app is isolated from production Nuvio; do not uninstall com.nuvio.media.\n'
printf 'Sign in to the benchmark app and leave it on Home when prompted.\n\n'

run_gradle_step "00-build-full-benchmarkRelease" \
  :androidApp:assembleFullBenchmarkRelease :baselineProfile:assembleFullBenchmarkRelease \
  -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
full_apk="$(find androidApp/build/outputs/apk -type f -name '*.apk' -path '*/full/benchmarkRelease/*' -print | head -n 1)"
[[ -n "$full_apk" ]] || fail "Could not find the full benchmarkRelease APK."
if ! adb -s "$DEVICE_SERIAL" install -r "$full_apk"; then
  fail "Could not install benchmark APK. If a previous benchmark build uses another signing key, save its results and remove only $BENCHMARK_PACKAGE. Do not uninstall production com.nuvio.media."
fi
adb -s "$DEVICE_SERIAL" shell monkey -p "$BENCHMARK_PACKAGE" 1 >/dev/null 2>&1 || fail "Could not launch the benchmark app."
read -r -p "Press Enter once the benchmark app is signed in and its Home screen is visible: " _

run_gradle_step "01-generate-full-nonMinifiedRelease-profile" \
  :baselineProfile:generateFullNonMinifiedReleaseBaselineProfile \
  -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
run_gradle_step "02-generate-playstore-nonMinifiedRelease-profile" \
  :baselineProfile:generatePlaystoreNonMinifiedReleaseBaselineProfile \
  -Pnuvio.android.distribution=playstore -Pnuvio.ios.distribution=appstore

for flavor in full playstore; do
  profile_dir="androidApp/src/${flavor}NonMinifiedRelease/generated/baselineProfiles"
  [[ -d "$profile_dir" ]] || fail "Missing generated profile directory: $profile_dir"
  find "$profile_dir" -maxdepth 1 -type f \( -name 'baseline-prof.txt' -o -name 'startup-prof.txt' \) -print -exec wc -c {} \; | tee "$OUTPUT_DIR/$flavor-generated-profiles.txt"
  grep -q 'baseline-prof.txt' "$OUTPUT_DIR/$flavor-generated-profiles.txt" || fail "No baseline-prof.txt for $flavor."
  baseline_profile_file="$(find "$profile_dir" -maxdepth 1 -type f -name 'baseline-prof.txt' -print -quit)"
  [[ -n "$baseline_profile_file" ]] || fail "No baseline-prof.txt for $flavor."
  [[ -s "$baseline_profile_file" ]] || fail "Generated baseline profile contains no rules: $baseline_profile_file."
  grep -Eq '[^[:space:]]' "$baseline_profile_file" || fail "Generated baseline profile contains no rules: $baseline_profile_file."
  startup_profile_file="$(find "$profile_dir" -maxdepth 1 -type f -name 'startup-prof.txt' -print -quit)"
  [[ -n "$startup_profile_file" ]] || fail "No startup-prof.txt for $flavor."
  [[ -s "$startup_profile_file" ]] || fail "Generated startup profile contains no rules: $startup_profile_file."
  grep -Eq '[^[:space:]]' "$startup_profile_file" || fail "Generated startup profile contains no rules: $startup_profile_file."
done

run_gradle_step "03-benchmark-full-benchmarkRelease" \
  :baselineProfile:connectedFullBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark \
  -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
require_startup_metrics "03-benchmark-full-benchmarkRelease"
run_gradle_step "04-benchmark-playstore-benchmarkRelease" \
  :baselineProfile:connectedPlaystoreBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark \
  -Pnuvio.android.distribution=playstore -Pnuvio.ios.distribution=appstore
require_startup_metrics "04-benchmark-playstore-benchmarkRelease"

: > "$OUTPUT_DIR/summary.txt"
for flavor in full playstore; do
  apk="$(find androidApp/build/outputs/apk -type f -name '*.apk' -path "*/$flavor/benchmarkRelease/*" -print | head -n 1)"
  [[ -n "$apk" ]] || fail "Missing optimized $flavor benchmark APK."
  profile_bytes="$(unzip -p "$apk" assets/dexopt/baseline.prof 2>/dev/null | wc -c | awk '{print $1}' || true)"
  [[ "$profile_bytes" =~ ^[0-9]+$ ]] || profile_bytes=0
  printf '%s benchmark APK: %s\n%s packaged binary Baseline Profile: %s bytes\n' "$flavor" "$apk" "$flavor" "$profile_bytes" >> "$OUTPUT_DIR/summary.txt"
  (( profile_bytes > 0 )) || fail "$flavor APK does not contain assets/dexopt/baseline.prof."
  (( profile_bytes < 1572864 )) || fail "$flavor compiled Baseline Profile is at least 1.5 MB."
done
for label in 03-benchmark-full-benchmarkRelease 04-benchmark-playstore-benchmarkRelease; do
  { printf '\n--- %s ---\n' "$label"; grep -E 'StartupBenchmark|timeToInitialDisplayMs|timeToFullDisplayMs|median|min|max|Median' "$OUTPUT_DIR/$label.log" || true; } >> "$OUTPUT_DIR/summary.txt"
done
cat "$OUTPUT_DIR/summary.txt"
printf '\nDo not commit generated profiles unless both TTID and TTFD improve repeatably without regressions.\nOutput directory: %s\n' "$OUTPUT_DIR"
