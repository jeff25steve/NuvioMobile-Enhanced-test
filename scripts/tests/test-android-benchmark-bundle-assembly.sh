#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
WORKFLOW="$ROOT_DIR/.github/workflows/android-startup-profile-benchmark.yml"
[ -f "$WORKFLOW" ] || { echo "FAIL: benchmark workflow not found: $WORKFLOW" >&2; exit 1; }

# The production benchmark workflow remains manual-only on normal branches.
# One isolated CI branch is allowed to push-trigger this same validation/build
# job; no other ref or automatic event may start it.
awk '
  /^on:[[:space:]]*$/ { in_on=1; seen_on=1; next }
  in_on && /^[^[:space:]#][^:]*:/ { in_on=0 }
  in_on && /^  workflow_dispatch:[[:space:]]*$/ { manual=1; next }
  in_on && /^  push:[[:space:]]*$/ { push=1; next }
  in_on && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { unexpected=1; next }
  in_on && push && /^    branches:[[:space:]]*$/ { branches=1; next }
  in_on && push && /^      - / {
    if ($0 == "      - ci/android-startup-abba-confirmation-20261116") {
      branch=1
      branch_count++
    } else {
      unexpected=1
    }
    next
  }
  in_on && push && /^    / { unexpected=1 }
  in_on && push && /^      / { unexpected=1 }
  END {
    if (!seen_on || !manual || !push || !branches || !branch || branch_count != 1 || unexpected) {
      print "FAIL: benchmark workflow may use workflow_dispatch plus only the isolated ABBA CI branch push; all other automatic triggers are forbidden." > "/dev/stderr"
      exit 1
    }
  }
' "$WORKFLOW" || exit 1

line_of() {
  grep -n -F -- "$1" "$WORKFLOW" | head -n 1 | cut -d: -f1
}

instructions_create="$(line_of "cat > phone-benchmark/START-HERE.txt <<'EOF'")"
instructions_check="$(line_of 'test -s phone-benchmark/START-HERE.txt')"
instructions_end="$(awk 'found && $0 == "          EOF" { print NR; exit } /cat > phone-benchmark\/START-HERE.txt <<.EOF./ { found=1 }' "$WORKFLOW")"
checksum_create="$(line_of 'xargs -0 sha256sum > SHA256SUMS.txt')"
checksum_verify="$(line_of 'sha256sum --check SHA256SUMS.txt')"
bundle_count_check="$(line_of 'Phone bundle must contain eight APKs, two scripts, START-HERE.txt and SHA256SUMS.txt.')"
upload_step="$(line_of '- name: Upload phone-ready benchmark APK bundle')"

for value in "$instructions_create" "$instructions_check" "$instructions_end" "$checksum_create" "$checksum_verify" "$bundle_count_check" "$upload_step"; do
  [ -n "$value" ] || { echo "FAIL: bundle workflow is missing a required assembly/checksum/upload operation." >&2; exit 1; }
done

[ "$instructions_create" -lt "$instructions_end" ] || {
  echo "FAIL: START-HERE.txt heredoc must have a closing delimiter after its creation." >&2
  exit 1
}
[ "$instructions_end" -lt "$instructions_check" ] || {
  echo "FAIL: START-HERE.txt must be created before it is checked for non-empty content." >&2
  exit 1
}
[ "$instructions_check" -lt "$checksum_create" ] && [ "$checksum_create" -lt "$checksum_verify" ] || {
  echo "FAIL: all bundle files must exist before checksums are generated and verified." >&2
  exit 1
}
[ "$checksum_verify" -lt "$bundle_count_check" ] && [ "$bundle_count_check" -lt "$upload_step" ] || {
  echo "FAIL: bundle integrity checks must complete before artifact upload." >&2
  exit 1
}

# The actual packaged R8 APKs must be audited, not inferred from variant names.
for required in \
  'manifest application-id' \
  'manifest debuggable' \
  'manifest print' \
  'android:shell="true"' \
  'outputs/mapping/' \
  'mapping.txt'; do
  grep -Fq -- "$required" "$WORKFLOW" || {
    echo "FAIL: benchmark APK contract audit is missing '$required'." >&2
    exit 1
  }
done


printf 'PASS: START-HERE creation precedes validation; checksum and bundle-count checks precede upload.\n'
