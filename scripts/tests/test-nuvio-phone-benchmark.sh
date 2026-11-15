#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNNER_SOURCE="$SCRIPT_DIR/nuvio-phone-benchmark.sh"
DESKTOP_RUNNER="$SCRIPT_DIR/run-android-startup-profile-benchmark.sh"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"

[ -f "$DESKTOP_RUNNER" ] || { echo "FAIL: desktop runner is missing." >&2; exit 1; }
[ -f "$GENERATOR_SOURCE" ] && [ -f "$BENCHMARK_SOURCE" ] || { echo "FAIL: baseline profile test sources are missing." >&2; exit 1; }
for source in "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE"; do
  grep -Fq 'TARGET_APP_PACKAGE = "com.nuvio.media.benchmark"' "$source" || {
    echo "FAIL: self-instrumenting tests must explicitly target the isolated Nuvio benchmark app." >&2
    exit 1
  }
  if grep -Fq 'getTargetContext().getPackageName()' "$source"; then
    echo "FAIL: self-instrumenting instrumentation context must not be mistaken for the target app package." >&2
    exit 1
  fi
done
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
  install) echo 'Success' ;;
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
  *macrobenchmark*) mkdir -p "$FAKE_MEDIA_DIR"; printf '{"benchmarks":[]}' > "$FAKE_MEDIA_DIR/fake-benchmarkData.json" ;;
  *BaselineProfile*) mkdir -p "$FAKE_MEDIA_DIR"; printf 'Lcom/nuvio/Foo;->bar()V\n' > "$FAKE_MEDIA_DIR/fake-baseline-prof-test.txt" ;;
esac
echo 'OK (1 test)'
STUB
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:$PATH"
export FAKE_COMMAND_LOG="$TMP/commands.log" FAKE_MEDIA_DIR="$TMP/media"
export NUVIO_PHONE_OUTPUT_DIR="$TMP/output" NUVIO_PHONE_MEDIA_ROOT="$TMP/media"
export NUVIO_PHONE_STAGE_DIR="$TMP/staged"

# Unsupported devices must fail before any APK installation.
if FAKE_API=32 sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile full >/dev/null 2>&1; then
  echo 'FAIL: API 32 should be rejected.' >&2
  exit 1
fi
[ ! -s "$FAKE_COMMAND_LOG" ] || { echo 'FAIL: unsupported device invoked package manager.' >&2; exit 1; }

for flavor in full playstore; do
  sh "$BUNDLE/nuvio-phone-benchmark.sh" install-profile "$flavor" >/dev/null
  if sh "$BUNDLE/nuvio-phone-benchmark.sh" measure "$flavor" >/dev/null 2>&1; then
    echo 'FAIL: measure must reject the profile-generation app state.' >&2
    exit 1
  fi
  sh "$BUNDLE/nuvio-phone-benchmark.sh" run "$flavor" >/dev/null
  test -n "$(find "$TMP/output/$flavor" -name '*baseline-prof*.txt' -print -quit)"
  test -n "$(find "$TMP/output/$flavor" -name '*benchmarkData.json' -print -quit)"
done

# Verify benchmark install never removes the real app and all commands target .benchmark.
! grep -q 'pm uninstall com.nuvio.media' "$FAKE_COMMAND_LOG"
grep -q 'monkey -p com.nuvio.media.benchmark 1' "$FAKE_COMMAND_LOG"
grep -Fq "pm install -r $TMP/staged/Nuvio-full-NonMinifiedRelease.apk" "$FAKE_COMMAND_LOG"
test -f "$TMP/staged/Nuvio-full-NonMinifiedRelease.apk"
test -f "$TMP/staged/Nuvio-playstore-BenchmarkRelease-Tests.apk"
test ! -f "$TMP/staged/Nuvio-full-BenchmarkRelease.apk" || test -f "$TMP/staged/Nuvio-full-BenchmarkRelease-Tests.apk"
grep -Fq 'instrumentation:com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner' "$FAKE_COMMAND_LOG" || true
grep -Fq 'com.nuvio.android.baselineprofile/androidx.test.runner.AndroidJUnitRunner' "$FAKE_COMMAND_LOG"
grep -q 'androidx.benchmark.enabledRules BaselineProfile' "$FAKE_COMMAND_LOG"
grep -q 'androidx.benchmark.enabledRules macrobenchmark' "$FAKE_COMMAND_LOG"
grep -q 'pm list instrumentation' "$FAKE_COMMAND_LOG"

sh -n "$BUNDLE/nuvio-phone-benchmark.sh"
printf 'PASS: phone runner covers both flavors, rejects API 32 and invalid run order, exports profile/benchmark artifacts, and never uninstalls production Nuvio.\n'
