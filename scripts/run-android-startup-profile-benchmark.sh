#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v adb >/dev/null 2>&1 || fail "Android Debug Bridge (adb) is not installed or is not on PATH."
command -v unzip >/dev/null 2>&1 || fail "unzip is required to inspect the packaged Baseline Profile."
[[ -x ./gradlew ]] || fail "Run this script from a checkout containing the executable Gradle wrapper."

if [[ ! -f local.properties ]]; then
  fail "local.properties is missing. Configure the same release keystore and app properties used for signed releases."
fi

for key in NUVIO_RELEASE_STORE_FILE NUVIO_RELEASE_STORE_PASSWORD NUVIO_RELEASE_KEY_ALIAS NUVIO_RELEASE_KEY_PASSWORD; do
  grep -Eq "^[[:space:]]*${key}[[:space:]]*=[[:space:]]*.+$" local.properties ||
    fail "local.properties is missing the required release-signing property: ${key}"
done

store_file="$(sed -n 's/^[[:space:]]*NUVIO_RELEASE_STORE_FILE[[:space:]]*=[[:space:]]*//p' local.properties | tail -n 1 | tr -d '\r')"
[[ -f "$store_file" ]] || fail "The release keystore referenced by NUVIO_RELEASE_STORE_FILE does not exist."

mapfile -t connected_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[[ "${#connected_devices[@]}" -eq 1 ]] ||
  fail "Connect exactly one authorized physical Android device over USB or Wireless debugging. Found ${#connected_devices[@]} ready devices."

DEVICE_SERIAL="${connected_devices[0]}"
API_LEVEL="$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_MODEL="$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.manufacturer | tr -d '\r') $(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model | tr -d '\r')"
KERNEL_QEMU="$(adb -s "$DEVICE_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
HARDWARE="$(adb -s "$DEVICE_SERIAL" shell getprop ro.hardware | tr -d '\r')"

[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || fail "Could not determine the connected device's Android API level."
[[ "$KERNEL_QEMU" != "1" && "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" && "$HARDWARE" != "qemu" ]] ||
  fail "This procedure requires a physical Android device, not an emulator."

if (( API_LEVEL < 33 )); then
  ROOT_ID="$(adb -s "$DEVICE_SERIAL" shell su -c id 2>/dev/null | tr -d '\r' || true)"
  [[ "$ROOT_ID" == *"uid=0"* ]] ||
    fail "Baseline Profile collection requires API 33+ without root, or API 28+ with root. Connected API level: $API_LEVEL."
fi

TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
OUTPUT_DIR="${NUVIO_STARTUP_BENCHMARK_OUTPUT:-$ROOT_DIR/build/android-startup-profile-benchmark/$TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"

cat > "$OUTPUT_DIR/device.txt" <<EOF
Device: $DEVICE_MODEL
Android API: $API_LEVEL
Run (UTC): $TIMESTAMP
Device serial: redacted
App package: com.nuvio.media
Flavors: full, playstore
Startup state: signed-in Home; enforced by nuvio_startup_ready
Startup benchmark: 10 cold iterations per compilation mode and flavor
EOF

printf 'Physical device: %s (API %s)\n' "$DEVICE_MODEL" "$API_LEVEL"
printf 'Logs and results: %s\n' "$OUTPUT_DIR"
printf '\nBefore continuing, make sure Nuvio is signed in and opens directly to the normal Home screen.\n'
printf 'The profile collector and benchmark both fail if onboarding/authentication or the Home-ready state is not reached.\n\n'

run_gradle_step() {
  local label="$1"
  shift
  printf '\n========== %s ==========\n' "$label"
  if ./gradlew --no-daemon --console=plain "$@" 2>&1 | tee "$OUTPUT_DIR/$label.log"; then
    return 0
  else
    local status=$?
    printf 'Gradle step %s failed (exit %s). See %s/%s.log\n' "$label" "$status" "$OUTPUT_DIR" "$label" >&2
    return "$status"
  fi
}

# The Baseline Profile plugin captures the unobfuscated nonMinifiedRelease
# variant and writes separate source profiles for each distribution flavor.
run_gradle_step "01-generate-full-release-profile" \
  :baselineProfile:generateFullReleaseBaselineProfile

run_gradle_step "02-generate-playstore-release-profile" \
  :baselineProfile:generatePlaystoreReleaseBaselineProfile

for flavor in full playstore; do
  profile_dir="androidApp/src/$flavor/generated/baselineProfiles"
  [[ -d "$profile_dir" ]] ||
    fail "Expected generated profile directory is missing: $profile_dir"
  find "$profile_dir" -maxdepth 1 -type f \\( -name 'baseline-prof.txt' -o -name 'startup-prof.txt' \\) -print -exec wc -c {} \\; |
    tee "$OUTPUT_DIR/$flavor-generated-profiles.txt"
  grep -q 'baseline-prof.txt' "$OUTPUT_DIR/$flavor-generated-profiles.txt" ||
    fail "No baseline-prof.txt was generated for $flavor."
done

# Build/run one flavor at a time so the Gradle split-APK switch and the app's
# same-package signed-in state are not changed between benchmark arms.
run_gradle_step "03-benchmark-full-release" \
  :baselineProfile:connectedFullBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

run_gradle_step "04-benchmark-playstore-release" \
  :baselineProfile:connectedPlaystoreBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

: > "$OUTPUT_DIR/summary.txt"
for flavor in full playstore; do
  apk="$(find androidApp/build/outputs/apk -type f -name '*.apk' -path "*/$flavor/benchmark/*" -print | head -n 1)"
  [[ -n "$apk" ]] || fail "Could not find the optimized $flavor benchmark APK."
  profile_bytes="$(unzip -p "$apk" assets/dexopt/baseline.prof 2>/dev/null | wc -c | awk '{print $1}' || true)"
  [[ "$profile_bytes" =~ ^[0-9]+$ ]] || profile_bytes=0
  printf '%s benchmark APK: %s\n' "$flavor" "$apk" >> "$OUTPUT_DIR/summary.txt"
  printf '%s packaged binary Baseline Profile: %s bytes\n' "$flavor" "$profile_bytes" >> "$OUTPUT_DIR/summary.txt"
  (( profile_bytes > 0 )) || fail "The $flavor benchmark APK does not contain assets/dexopt/baseline.prof."
  (( profile_bytes < 1572864 )) || fail "The $flavor compiled Baseline Profile is at least 1.5 MB."
done

for label in 03-benchmark-full-release 04-benchmark-playstore-release; do
  {
    printf '\n--- %s ---\n' "$label"
    grep -E 'StartupBenchmark|timeToInitialDisplayMs|timeToFullDisplayMs|median|min|max|Median' "$OUTPUT_DIR/$label.log" || true
  } >> "$OUTPUT_DIR/summary.txt"
done

printf '\n========== RESULT FILES ==========\n'
cat "$OUTPUT_DIR/summary.txt"
printf '\nRaw Macrobenchmark logs and traces are retained in Gradle build outputs.\n'
printf 'Generated profile files are in androidApp/src/full/generated/baselineProfiles and androidApp/src/playstore/generated/baselineProfiles.\n'
printf 'Do not commit new profiles until both flavors show no TTID or TTFD regression and a repeatable improvement.\n'
printf 'Output directory: %s\n' "$OUTPUT_DIR"
