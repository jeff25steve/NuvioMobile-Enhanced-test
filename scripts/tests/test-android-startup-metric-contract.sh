#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"
PHONE_RUNNER="$ROOT_DIR/scripts/run-android-startup-profile-benchmark.sh"

[ -f "$BENCHMARK_SOURCE" ] || {
  echo "FAIL: StartupBenchmark source not found: $BENCHMARK_SOURCE" >&2
  exit 1
}
[ -f "$PHONE_RUNNER" ] || {
  echo "FAIL: phone benchmark runner not found: $PHONE_RUNNER" >&2
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

printf 'PASS: both cold-start Macrobenchmark cases are declared and use the shared TTID/TTFD measurement.\n'
