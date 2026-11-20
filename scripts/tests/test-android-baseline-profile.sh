#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
PROFILE_SOURCE="$ROOT_DIR/androidApp/src/main/baseline-prof.txt"
GENERATOR_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/BaselineProfileGenerator.java"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
ANDROID_BUILD_SOURCE="$ROOT_DIR/androidApp/build.gradle.kts"
PHONE_WORKFLOW_SOURCE="$ROOT_DIR/.github/workflows/android-startup-profile-benchmark.yml"
PHONE_RUNNER_SOURCE="$ROOT_DIR/scripts/nuvio-phone-benchmark.sh"
DESKTOP_RUNNER_SOURCE="$ROOT_DIR/scripts/run-android-startup-profile-benchmark.sh"

for source in "$PROFILE_SOURCE" "$GENERATOR_SOURCE" "$BENCHMARK_SOURCE" "$ANDROID_BUILD_SOURCE" "$PHONE_WORKFLOW_SOURCE" "$PHONE_RUNNER_SOURCE" "$DESKTOP_RUNNER_SOURCE"; do
  [ -s "$source" ] || {
    echo "FAIL: required Baseline Profile source is missing or empty: $source" >&2
    exit 1
  }
done

# Audit the committed human-readable profile itself. This is deliberately a
# text-integrity check; the compiled assets/dexopt/baseline.prof size and ART
# installation status can only be verified from a built artifact/device.
awk '
  NF {
    rules++
    if (seen[$0]++) duplicates++
    if ($0 ~ /\/generated\//) generatedPathRules++
    if ($0 !~ /^[SHP]*L[^;]+;(->.*)?$/) malformed++
    if ($0 ~ /^L[^;]+;$/) classes++
    if ($0 ~ /^[SHP]*L[^;]+;->/) methods++
    flags = $0
    sub(/L.*/, "", flags)
    if (index(flags, "S") > 0 && $0 ~ /;->/) startupMethods++
    if (index(flags, "P") > 0 && $0 ~ /;->/) baselineMethods++
  }
  END {
    if (rules == 0 || duplicates > 0 || malformed > 0 ||
        classes == 0 || methods == 0 || startupMethods == 0 || baselineMethods == 0) {
      printf "FAIL: profile integrity: rules=%d generatedPathRules=%d duplicates=%d malformed=%d classes=%d methods=%d startupMethods=%d baselineMethods=%d\n", \
        rules, generatedPathRules, duplicates, malformed, classes, methods, startupMethods, baselineMethods > "/dev/stderr"
      exit 1
    }
    generatedPercent = rules > 0 ? (generatedPathRules * 100.0 / rules) : 0
    printf "PROFILE: rules=%d generatedPathRules=%d generatedPathPercent=%.1f classes=%d methods=%d startupMethods=%d baselineMethods=%d duplicates=0 malformed=0\n", \
      rules, generatedPathRules, generatedPercent, classes, methods, startupMethods, baselineMethods
  }
' "$PROFILE_SOURCE"

startup_collect_block="$(awk '
  /baselineProfileRule.collect\(/ { collection++ }
  collection == 1 { print }
  collection == 1 && /^[[:space:]]*\);/ { exit }
' "$GENERATOR_SOURCE")"
browse_collect_block="$(awk '
  /baselineProfileRule.collect\(/ { collection++ }
  collection == 2 { print }
  collection == 2 && /^[[:space:]]*\);/ { exit }
' "$GENERATOR_SOURCE")"

printf '%s\n' "$startup_collect_block" | grep -Fq '"startup",' || {
  echo "FAIL: the launcher journey must retain the startup profile output prefix." >&2
  exit 1
}
startup_flags="$(printf '%s\n' "$startup_collect_block" | grep -c '^                true,$' || true)"
[ "$startup_flags" -eq 2 ] || {
  echo "FAIL: launcher startup must set includeInStartupProfile=true and strictStability=true." >&2
  exit 1
}
printf '%s\n' "$browse_collect_block" | grep -Fq '"home_browse", false, true,' || {
  echo "FAIL: Home browse must remain Baseline-Profile-only with strict stability enabled." >&2
  exit 1
}

for collect_block in "$startup_collect_block" "$browse_collect_block"; do
  printf '%s\n' "$collect_block" | grep -Fq '                15,' &&
    printf '%s\n' "$collect_block" | grep -Fq '                3,' &&
    printf '%s\n' "$collect_block" | grep -Fq 'rule -> true' || {
    echo "FAIL: both journeys must retain 15 max iterations, 3 stable iterations and an unfiltered complete rule set." >&2
    exit 1
  }
done

grep -Fq 'CompilationMode.None.INSTANCE' "$BENCHMARK_SOURCE" &&
  grep -Fq 'BaselineProfileMode.Disable' "$BENCHMARK_SOURCE" &&
  grep -Fq 'BaselineProfileMode.Require' "$BENCHMARK_SOURCE" || {
  echo "FAIL: startup comparison must retain no-compilation, partial-without-profile and required-profile arms." >&2
  exit 1
}
grep -Fq 'private static final int ITERATIONS = 10;' "$BENCHMARK_SOURCE" || {
  echo "FAIL: startup comparison iteration count changed without an explicit review." >&2
  exit 1
}

# The desktop runner must not silently summarize a partial JUnit benchmark run.
desktop_metric_check="$(sed -n '/^require_startup_metrics() {/,/^}/p' "$DESKTOP_RUNNER_SOURCE")"
for benchmark_case in \
  startupNoCompilation \
  startupPartialWithoutBaselineProfile \
  startupWithBaselineProfile; do
  printf '%s\n' "$desktop_metric_check" | grep -Fq "$benchmark_case" || {
    echo "FAIL: desktop runner must require the '$benchmark_case' result before summarizing a flavor." >&2
    exit 1
  }
done
for metric in timeToInitialDisplayMs timeToFullDisplayMs artJitCount artJitSumMs artVerifyClassCount artVerifyClassSumMs; do
  printf '%s\n' "$desktop_metric_check" | grep -Fq "$metric" || {
    echo "FAIL: desktop runner must validate '$metric' for all three startup cases." >&2
    exit 1
  }
done

# The phone bundle's embedded instructions must describe the same three arms
# as StartupBenchmark.java and the phone-side validator.
for expected in \
  'CompilationMode.None (no pre-compilation).' \
  'CompilationMode.Partial(BaselineProfileMode.Disable, 3)' \
  'CompilationMode.Partial(BaselineProfileMode.Require)' \
  '10 cold launches in each of these three comparison arms'; do
  grep -Fq "$expected" "$PHONE_WORKFLOW_SOURCE" || {
    echo "FAIL: generated phone instructions are missing current comparison detail '$expected'." >&2
    exit 1
  }
done
if grep -Fq 'runs 10 cold launches with CompilationMode.None and 10 with' "$PHONE_WORKFLOW_SOURCE"; then
  echo "FAIL: generated phone instructions still describe the obsolete two-arm comparison." >&2
  exit 1
fi

# The checked-in profile text is not the compiled profile installed by ART.
# Protect the separate capture/run variants and the packaged binary inspection.
grep -Fq 'create("nonMinifiedRelease")' "$ANDROID_BUILD_SOURCE" || {
  echo "FAIL: readable profile collection must use the dedicated nonMinifiedRelease variant." >&2
  exit 1
}
benchmark_variant_block="$(sed -n '/create("benchmarkRelease") {/,/^[[:space:]]*}/p' "$ANDROID_BUILD_SOURCE")"
printf '%s\n' "$benchmark_variant_block" | grep -Fq 'isMinifyEnabled = true' &&
  printf '%s\n' "$benchmark_variant_block" | grep -Fq 'isShrinkResources = true' &&
  printf '%s\n' "$benchmark_variant_block" | grep -Fq 'applicationIdSuffix = ".benchmark"' &&
  printf '%s\n' "$benchmark_variant_block" | grep -Fq 'signingConfig = signingConfigs.getByName("debug")' || {
  echo "FAIL: benchmarkRelease must remain optimized, isolated under the .benchmark package ID and locally debug-signed." >&2
  exit 1
}
capture_variant_block="$(sed -n '/create("nonMinifiedRelease") {/,/^[[:space:]]*}/p' "$ANDROID_BUILD_SOURCE")"
printf '%s\n' "$capture_variant_block" | grep -Fq 'applicationIdSuffix = ".benchmark"' &&
  printf '%s\n' "$capture_variant_block" | grep -Fq 'signingConfig = signingConfigs.getByName("debug")' || {
  echo "FAIL: nonMinifiedRelease profile capture must use the same isolated package ID and local signing identity as benchmarkRelease." >&2
  exit 1
}
grep -Fq 'implementation(libs.androidx.profileinstaller)' "$ANDROID_BUILD_SOURCE" || {
  echo "FAIL: the app must retain AndroidX ProfileInstaller for packaged Baseline Profile delivery." >&2
  exit 1
}
grep -Fq 'assets/dexopt/baseline.prof' "$PHONE_WORKFLOW_SOURCE" &&
  grep -Fq 'profile_bytes < 1572864' "$PHONE_WORKFLOW_SOURCE" &&
  grep -Fq 'profile_bytes > 0' "$PHONE_WORKFLOW_SOURCE" || {
  echo "FAIL: the APK workflow must validate the non-empty compiled Baseline Profile and enforce its 1.5 MiB ceiling." >&2
  exit 1
}
grep -Fq 'install-profile:full' "$PHONE_RUNNER_SOURCE" &&
  grep -Fq 'Nuvio-$FLAVOR-NonMinifiedRelease.apk' "$PHONE_RUNNER_SOURCE" &&
  grep -Fq 'Nuvio-$FLAVOR-BenchmarkRelease.apk' "$PHONE_RUNNER_SOURCE" || {
  echo "FAIL: phone runner must separate profile-capture installation from optimized benchmark installation." >&2
  exit 1
}
grep -Fq 'A newly collected profile' "$PHONE_WORKFLOW_SOURCE" &&
  grep -Fq 'is not inserted into the APK automatically' "$PHONE_WORKFLOW_SOURCE" || {
  echo "FAIL: bundle instructions must state that collected text rules require a new packaged build." >&2
  exit 1
}

printf 'PASS: Baseline Profile text integrity, capture/run variants, packaged profile delivery and comparison arms are guarded.\n'
