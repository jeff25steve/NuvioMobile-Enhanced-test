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

# Sentry must remain in the measured pre-super section so early startup
# crashes continue to be captured without silently moving SDK startup work.
pre_super_block="$(printf '%s\\n' "$on_create" | sed -n '/traceStartupSection("Nuvio.Startup.preSuper") {/,/^        }$/p')"
sentry_start_count="$(printf '%s\\n' "$on_create" | grep -Fc 'SentryInitializer.start(application)' || true)"
printf '%s\\n' "$pre_super_block" | grep -Fq 'SentryInitializer.start(application)' &&
  [ "$sentry_start_count" -eq 1 ] || {
  echo "FAIL: Sentry must be initialized exactly once inside the measured pre-super startup section." >&2
  exit 1
}
sentry_line="$(printf '%s\\n' "$on_create" | grep -nF 'SentryInitializer.start(application)' | cut -d: -f1)"
[ -n "$sentry_line" ] && [ "$sentry_line" -lt "$super_line" ] || {
  echo "FAIL: Sentry initialization must remain before super.onCreate for early crash capture." >&2
  exit 1
}

# Protect the lifecycle boundary and require every synchronous initializer
# in onCreate to be inside one of the measured groups.
pre_super_line="$(printf '%s\n' "$on_create" | grep -nF 'traceStartupSection("Nuvio.Startup.preSuper")' | cut -d: -f1)"
super_line="$(printf '%s\n' "$on_create" | grep -nF 'super.onCreate(savedInstanceState)' | cut -d: -f1)"
post_super_line="$(printf '%s\n' "$on_create" | grep -nF 'traceStartupSection("Nuvio.Startup.identityNetwork")' | cut -d: -f1)"
[ -n "$pre_super_line" ] && [ -n "$super_line" ] && [ -n "$post_super_line" ] &&
  [ "$pre_super_line" -lt "$super_line" ] && [ "$super_line" -lt "$post_super_line" ] || {
  echo "FAIL: the pre-super trace group must remain before super.onCreate and other groups after it." >&2
  exit 1
}

initializer_coverage="$(printf '%s\n' "$on_create" | awk '
  /^[[:space:]]*traceStartupSection\("/ { in_trace=1; next }
  /^        }$/ && in_trace { in_trace=0; next }
  /\.initialize\(/ { total++; if (in_trace) traced++ }
  END { printf "%d %d\n", total, traced }
')"
set -- $initializer_coverage
[ "$#" -eq 2 ] && [ "$1" -gt 0 ] && [ "$1" -eq "$2" ] || {
  echo "FAIL: every onCreate initializer must remain inside a named trace group." >&2
  exit 1
}

printf 'PASS: Android startup trace sections are centralized, balanced, and ordered.\n'
