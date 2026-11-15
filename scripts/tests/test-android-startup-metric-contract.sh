#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
PHONE_RUNNER="$ROOT_DIR/scripts/run-android-startup-profile-benchmark.sh"
WORKFLOW_SOURCE="$ROOT_DIR/.github/workflows/android-startup-profile-benchmark.yml"

[ -f "$BENCHMARK_SOURCE" ] || {
  echo "FAIL: StartupBenchmark source not found: $BENCHMARK_SOURCE" >&2
  exit 1
}
[ -f "$PHONE_RUNNER" ] || {
  echo "FAIL: phone benchmark runner not found: $PHONE_RUNNER" >&2
  exit 1
}
[ -f "$WORKFLOW_SOURCE" ] || {
  echo "FAIL: benchmark workflow not found: $WORKFLOW_SOURCE" >&2
  exit 1
}

awk '
  /^[[:space:]]*@Test[[:space:]]*$/ { annotated=1; next }
  /^[[:space:]]*public void startupNoCompilation[[:space:]]*\(/ && annotated { found=1 }
  { if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*@Test[[:space:]]*$/) annotated=0 }
  END { exit !found }
' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare a JUnit test for the no-compilation baseline." >&2
  exit 1
}
grep -Fq 'runColdStartupBenchmark(new CompilationMode.None())' "$BENCHMARK_SOURCE" || {
  echo "FAIL: no-compilation test must execute the shared cold-start measurement." >&2
  exit 1
}
awk '
  /^[[:space:]]*@Test[[:space:]]*$/ { annotated=1; next }
  /^[[:space:]]*public void startupWithBaselineProfile[[:space:]]*\(/ && annotated { found=1 }
  { if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*@Test[[:space:]]*$/) annotated=0 }
  END { exit !found }
' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare a JUnit test for the packaged Baseline Profile." >&2
  exit 1
}
grep -Fq 'runColdStartupBenchmark(new CompilationMode.Partial(BaselineProfileMode.Require))' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Baseline Profile test must require the packaged profile during partial compilation." >&2
  exit 1
}
grep -Fq 'new StartupTimingMetric()' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both comparison arms must collect StartupTimingMetric." >&2
  exit 1
}
grep -Fq 'StartupMode.COLD' "$BENCHMARK_SOURCE" || {
  echo "FAIL: both comparison arms must measure cold starts." >&2
  exit 1
}
grep -Fq 'private static final int ITERATIONS = 10;' "$BENCHMARK_SOURCE" || {
  echo "FAIL: startup comparison must retain ten measured iterations per arm." >&2
  exit 1
}

grep -Fq 'for metric in timeToInitialDisplayMs timeToFullDisplayMs; do' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must require both TTID and TTFD output for every flavor." >&2
  exit 1
}
grep -Fq 'did not emit $metric' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must fail clearly when a startup metric is missing." >&2
  exit 1
}

grep -Fq 'require_startup_metrics() {' "$PHONE_RUNNER" || {
  echo "FAIL: phone runner must centralize the TTID/TTFD validation for each flavor." >&2
  exit 1
}

line_of() {
  grep -n -F -- "$1" "$PHONE_RUNNER" | head -n 1 | cut -d: -f1
}
full_run="$(line_of 'run_gradle_step "03-benchmark-full-benchmarkRelease"')"
full_check="$(line_of 'require_startup_metrics "03-benchmark-full-benchmarkRelease"')"
playstore_run="$(line_of 'run_gradle_step "04-benchmark-playstore-benchmarkRelease"')"
playstore_check="$(line_of 'require_startup_metrics "04-benchmark-playstore-benchmarkRelease"')"
for line in "$full_run" "$full_check" "$playstore_run" "$playstore_check"; do
  [ -n "$line" ] || {
    echo "FAIL: phone runner is missing a per-flavor startup result validation point." >&2
    exit 1
  }
done
[ "$full_run" -lt "$full_check" ] &&
[ "$full_check" -lt "$playstore_run" ] &&
[ "$playstore_run" -lt "$playstore_check" ] || {
  echo "FAIL: each flavor's TTID/TTFD output must be validated before the next flavor starts." >&2
  exit 1
}

# Every source regression must run exactly once in the pre-build workflow.
for test_script in \
  test-nuvio-phone-benchmark.sh \
  test-android-benchmark-bundle-assembly.sh \
  test-android-startup-readiness.sh \
  test-android-startup-metric-contract.sh; do
  count="$(awk -v expected="sh scripts/tests/$test_script" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      if (line == expected) count++
    }
    END { print count + 0 }
  ' "$WORKFLOW_SOURCE")"
  [ "$count" -eq 1 ] || {
    echo "FAIL: workflow must invoke sh scripts/tests/$test_script exactly once; found $count." >&2
    exit 1
  }
done

printf 'PASS: both cold-start Macrobenchmark cases are declared and use the shared TTID/TTFD measurement.\n'
