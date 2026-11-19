#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/settings/AppIconPlatform.android.kt"
MANIFEST="$ROOT_DIR/androidApp/src/main/AndroidManifest.xml"

[ -f "$SOURCE" ] && [ -f "$MANIFEST" ] || {
  echo "FAIL: Android app-icon platform source or launcher manifest is missing." >&2
  exit 1
}

default_manifest_block="$(sed -n '/android:name="com.nuvio.app.launcher.AppIconDefault"/,/^[[:space:]]*<\/activity>/p' "$MANIFEST")"
printf '%s\n' "$default_manifest_block" | grep -Fq 'android:enabled="true"' || {
  echo "FAIL: the default launcher alias must remain enabled by the main manifest." >&2
  exit 1
}

repair_block="$(sed -n '/^    private fun restoreDefaultIfNeeded(context: Context) {$/,/^    }$/p' "$SOURCE")"
[ -n "$repair_block" ] || {
  echo "FAIL: could not locate the launcher-alias recovery function." >&2
  exit 1
}

printf '%s\n' "$repair_block" | grep -Fq 'val defaultComponent = component(context, defaultClass)' || {
  echo "FAIL: launcher recovery must resolve the default alias once." >&2
  exit 1
}
printf '%s\n' "$repair_block" | grep -Fq 'getComponentEnabledSetting(defaultComponent)' || {
  echo "FAIL: launcher recovery must inspect the default alias before scanning alternatives." >&2
  exit 1
}
printf '%s\n' "$repair_block" | awk '
  /getComponentEnabledSetting\(defaultComponent\)/ { query = NR }
  /PackageManager.COMPONENT_ENABLED_STATE_DISABLED/ { disabled = NR }
  /return/ { if (query > 0 && disabled > query && scan == 0) early_return = NR }
  /val hasEnabledAlternative = launcherComponents.any/ { scan = NR }
  END { exit !(query > 0 && disabled > query && early_return > disabled && scan > early_return) }
' || {
  echo "FAIL: the manifest-enabled default alias must short-circuit unless explicitly disabled." >&2
  exit 1
}
printf '%s\n' "$repair_block" | grep -Fq 'val hasEnabledAlternative = launcherComponents.any' || {
  echo "FAIL: launcher recovery must scan alternatives only after confirming the default alias is disabled." >&2
  exit 1
}
printf '%s\n' "$repair_block" | grep -Fq 'className != defaultClass' &&
  printf '%s\n' "$repair_block" | grep -Fq 'COMPONENT_ENABLED_STATE_ENABLED' || {
  echo "FAIL: the recovery scan must preserve any explicitly enabled alternative launcher alias." >&2
  exit 1
}
printf '%s\n' "$repair_block" | grep -Fq 'setComponentEnabledSetting(' &&
  printf '%s\n' "$repair_block" | grep -Fq 'defaultComponent,' || {
  echo "FAIL: recovery must restore the already-resolved default component." >&2
  exit 1
}

printf '%s\n' "$repair_block" | awk '
  /getComponentEnabledSetting\(defaultComponent\)/ { default_query = NR }
  /val hasEnabledAlternative = launcherComponents.any/ { alternative_scan = NR }
  /setComponentEnabledSetting\(/ { restore = NR }
  END { exit !(default_query > 0 && alternative_scan > default_query && restore > alternative_scan) }
' || {
  echo "FAIL: default-state short-circuit must precede the alternative scan and any repair." >&2
  exit 1
}

printf 'PASS: manifest-default launcher recovery exits before scanning alternative aliases.\n'
