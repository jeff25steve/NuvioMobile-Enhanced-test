#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
BENCHMARK_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StartupBenchmark.java"

[ -f "$BENCHMARK_SOURCE" ] || {
  echo "FAIL: StartupBenchmark source not found: $BENCHMARK_SOURCE" >&2
  exit 1
}

grep -Eq '@Test[[:space:]]+public void startupNoCompilation\(' "$BENCHMARK_SOURCE" || {
  echo "FAIL: Macrobenchmark must declare a JUnit test for the no-compilation baseline." >&2
  exit 1
}
grep -Fq 'runColdStartupBenchmark(new CompilationMode.None())' "$BENCHMARK_SOURCE" || {
  echo "FAIL: no-compilation test must execute the shared cold-start measurement." >&2
  exit 1
}
grep -Eq '@Test[[:space:]]+public void startupWithBaselineProfile\(' "$BENCHMARK_SOURCE" || {
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

printf 'PASS: both cold-start Macrobenchmark cases are declared and use the shared TTID/TTFD measurement.\n'
