#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
MAIN_ACTIVITY_SOURCE="${1:-$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt}"

[ -f "$MAIN_ACTIVITY_SOURCE" ] || {
  echo "FAIL: MainActivity source not found: $MAIN_ACTIVITY_SOURCE" >&2
  exit 1
}

# Compose only maps testTag to AccessibilityNodeInfo.viewIdResourceName for
# descendants of the semantics node that enables testTagsAsResourceId. Keep
# the readiness marker on a child Box rather than the root Box itself.
if ! awk '
  /\.semantics \{ testTagsAsResourceId = true \}/ { scope_enabled = 1; next }
  scope_enabled && /Box\(/ { descendant_box = 1 }
  descendant_box && /\.testTag\("nuvio_startup_ready"\)/ { found = 1 }
  END { exit !found }
' "$MAIN_ACTIVITY_SOURCE"; then
  echo "FAIL: nuvio_startup_ready must be a descendant Box inside the testTagsAsResourceId semantics subtree." >&2
  exit 1
fi

printf 'PASS: Nuvio startup readiness testTag is exposed from a descendant Compose semantics node.\n'
