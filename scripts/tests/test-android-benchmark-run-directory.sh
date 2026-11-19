#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
RUNNER_SOURCE="$ROOT_DIR/scripts/nuvio-phone-benchmark.sh"

[ -f "$RUNNER_SOURCE" ] || {
  echo "FAIL: phone benchmark runner is missing: $RUNNER_SOURCE" >&2
  exit 1
}

# Execute the real function body with a fixed clock. This makes same-second
# collisions deterministic without sleeping or launching a device benchmark.
function_source="$(awk '
  $0 == "new_run_dir() {" { capturing = 1 }
  capturing { print }
  capturing && $0 == "}" { exit }
' "$RUNNER_SOURCE")"
[ -n "$function_source" ] || {
  echo "FAIL: could not extract new_run_dir from the phone benchmark runner." >&2
  exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

date() {
  printf '20261119-235959\n'
}

# new_run_dir includes a two-second artifact marker delay. Avoid a real wait;
# only directory allocation is under test.
sleep() {
  :
}

OUTPUT_ROOT="$TMP/output"
FLAVOR=full
ACTION=measure
eval "$function_source"

new_run_dir
first_dir="$RUN_DIR"
first_marker="$MARKER"
[ -f "$first_marker" ] || fail "first run marker was not created"

new_run_dir
second_dir="$RUN_DIR"
second_marker="$MARKER"
[ -f "$second_marker" ] || fail "second run marker was not created"
[ "$first_dir" != "$second_dir" ] || fail "same-second runs reused the same result directory"
[ -f "$first_marker" ] || fail "allocating a second run removed or replaced the first run marker"

new_run_dir
third_dir="$RUN_DIR"
[ "$third_dir" != "$first_dir" ] && [ "$third_dir" != "$second_dir" ] || fail "third same-second run reused an existing result directory"
[ -f "$first_marker" ] && [ -f "$second_marker" ] && [ -f "$MARKER" ] || fail "same-second run markers are not isolated"

# Race separate shell processes against one fixed timestamp. Atomic directory
# creation must reserve one path per process without a lock file or shared state.
mkdir -p "$TMP/concurrent-paths"
allocation_count=16
allocation_index=1
while [ "$allocation_index" -le "$allocation_count" ]; do
  (
    new_run_dir
    printf '%s\n' "$RUN_DIR" > "$TMP/concurrent-paths/$allocation_index"
  ) &
  allocation_index=$((allocation_index + 1))
done
wait

: > "$TMP/concurrent-path-list"
observed_allocations=0
for allocation_file in "$TMP"/concurrent-paths/*; do
  [ -f "$allocation_file" ] || continue
  IFS= read -r allocated_dir < "$allocation_file"
  [ -d "$allocated_dir" ] && [ -f "$allocated_dir/.started" ] || fail "concurrent allocation did not preserve its unique directory and marker: $allocated_dir"
  printf '%s\n' "$allocated_dir" >> "$TMP/concurrent-path-list"
  observed_allocations=$((observed_allocations + 1))
done
[ "$observed_allocations" -eq "$allocation_count" ] || fail "expected $allocation_count concurrent allocations but observed $observed_allocations"
duplicate_allocations="$(sort "$TMP/concurrent-path-list" | uniq -d | wc -l | tr -d ' ')"
[ "$duplicate_allocations" -eq 0 ] || fail "concurrent invocations reused $duplicate_allocations result directory paths"

printf 'PASS: same-second benchmark invocations receive distinct result directories and retain earlier run markers.\n'
