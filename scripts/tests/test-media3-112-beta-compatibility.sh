#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CATALOG="$ROOT/gradle/libs.versions.toml"
SOURCE_ROOT="$ROOT/composeApp/src"

fail() {
    printf 'FAIL: %s\\n' "$1" >&2
    exit 1
}

[[ -f "$CATALOG" ]] || fail "version catalog not found: $CATALOG"
[[ -d "$SOURCE_ROOT" ]] || fail "production source directory not found: $SOURCE_ROOT"

grep -Eq '^androidx-media3 = "1\\.12\\.0-beta01"$' "$CATALOG" \\
    || fail "all Media3 artifacts must use androidx-media3 1.12.0-beta01"

MIN_SDK="$(sed -nE 's/^android-minSdk = "([0-9]+)"$/\\1/p' "$CATALOG" | head -n 1)"
[[ -n "$MIN_SDK" ]] || fail "android-minSdk is missing or not a quoted integer"
(( MIN_SDK >= 24 )) || fail "Media3 1.12 requires minSdk 24; found $MIN_SDK"

MEDIA3_MODULE_COUNT="$(grep -c 'module = "androidx.media3:' "$CATALOG" || true)"
(( MEDIA3_MODULE_COUNT > 0 )) || fail "no catalog-managed Media3 modules were found"
if awk '/module = "androidx.media3:/ && $0 !~ /version.ref = "androidx-media3"/ { print; bad = 1 } END { exit !bad }' "$CATALOG"; then
    fail "every Media3 module must use version.ref = \\"androidx-media3\\""
fi

if grep -R -n -E 'DashUtil\\.buildDataSpec|setAsyncCryptoSynchronizationEnabled|TextureAssetLoader' \\
    "$SOURCE_ROOT" --include='*.kt'; then
    fail "production source references an API removed in Media3 1.12"
fi

printf 'PASS: Media3 1.12.0-beta01 catalog alignment, minSdk %s and removed-API checks\\n' "$MIN_SDK"
