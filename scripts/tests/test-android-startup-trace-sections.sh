#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt"

[ -f "$SOURCE" ] || {
  echo "FAIL: MainActivity source not found: $SOURCE" >&2
  exit 1
}

grep -Fq 'import android.os.Trace' "$SOURCE" || {
  echo "FAIL: MainActivity must import the Android Trace API." >&2
  exit 1
}

# Trace sections must be balanced in a finally block so thrown initializer
# failures cannot leave the thread's trace stack unbalanced.
trace_helper="$(sed -n '/^private inline fun traceStartupSection(/,/^}$/p' "$SOURCE")"
[ -n "$trace_helper" ] || {
  echo "FAIL: startup trace helper is missing." >&2
  exit 1
}
printf '%s\n' "$trace_helper" | grep -Fq 'Trace.beginSection(sectionName)' &&
  printf '%s\n' "$trace_helper" | grep -Fq 'finally {' &&
  printf '%s\n' "$trace_helper" | grep -Fq 'Trace.endSection()' || {
  echo "FAIL: trace sections must pair begin/end in try/finally." >&2
  exit 1
}
[ "$(grep -Fc 'Trace.beginSection(sectionName)' "$SOURCE")" -eq 1 ] &&
  [ "$(grep -Fc 'Trace.endSection()' "$SOURCE")" -eq 1 ] || {
  echo "FAIL: expected one centralized, balanced Trace begin/end pair." >&2
  exit 1
}

# The five named groups must stay on the synchronous onCreate path, in order.
on_create="$(sed -n '/^    override fun onCreate(savedInstanceState: Bundle?)/,/^    }$/p' "$SOURCE")"
actual_sections="$(printf '%s\n' "$on_create" | sed -n 's/^[[:space:]]*traceStartupSection("\([^"]*\)").*/\1/p')"
expected_sections='Nuvio.Startup.preSuper
Nuvio.Startup.identityNetwork
Nuvio.Startup.coreStorage
Nuvio.Startup.profileMetadata
Nuvio.Startup.integrationsDownloads'
[ "$actual_sections" = "$expected_sections" ] || {
  echo "FAIL: startup trace groups are missing, duplicated, or out of order." >&2
  exit 1
}

[ "$(printf '%s\n' "$actual_sections" | grep -c '^Nuvio\.Startup\.' )" -eq 5 ] || {
  echo "FAIL: expected exactly five coarse startup trace groups." >&2
  exit 1
}

# Every constant section label must remain within android.os.Trace's limit.
printf '%s\n' "$actual_sections" | awk 'length($0) > 127 { exit 1 }' || {
  echo "FAIL: startup trace label exceeds 127 characters." >&2
  exit 1
}

printf 'PASS: Android startup trace sections are centralized, balanced, and ordered.\n'
