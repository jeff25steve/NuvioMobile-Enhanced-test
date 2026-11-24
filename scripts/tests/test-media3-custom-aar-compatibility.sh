#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
CATALOG="$ROOT_DIR/gradle/libs.versions.toml"
ANDROID_BUILD="$ROOT_DIR/composeApp/build.gradle.kts"
SOURCE_ROOT="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/player"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

[ -f "$CATALOG" ] || fail "Media3 version catalog is missing."
[ -f "$ANDROID_BUILD" ] || fail "composeApp Gradle build file is missing."
[ -d "$SOURCE_ROOT" ] || fail "Android player source directory is missing."

grep -Eq '^androidx-media3 = "1\.8\.0"$' "$CATALOG" \
    || fail "Nuvio custom Media3 AARs require the verified 1.8.0 dependency baseline."
MIN_SDK="$(sed -nE 's/^android-minSdk = "([0-9]+)"$/\1/p' "$CATALOG" | head -n 1)"
[ "$MIN_SDK" = "24" ] || fail "Expected the existing minSdk 24 baseline; found ${MIN_SDK:-missing}."

MEDIA3_MODULE_COUNT="$(grep -c 'module = "androidx.media3:' "$CATALOG" || true)"
[ "$MEDIA3_MODULE_COUNT" -gt 0 ] || fail "No catalog-managed Media3 modules were found."
if awk '/module = "androidx.media3:/ && $0 !~ /version.ref = "androidx-media3"/ { print; bad = 1 } END { exit !bad }' "$CATALOG"; then
    fail "Every catalog-managed Media3 module must use the shared androidx-media3 version."
fi

grep -Fq 'fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar")))' "$ANDROID_BUILD" \
    || fail "The five local custom Media3 AARs are not wired into composeApp."

while IFS=' ' read -r file expected; do
    [ -n "$file" ] || continue
    path="$ROOT_DIR/composeApp/libs/$file"
    [ -s "$path" ] || fail "Required custom AAR is missing or empty: $file"
    actual="$(git -C "$ROOT_DIR" hash-object "$path")"
    [ "$actual" = "$expected" ] || fail "Custom AAR fingerprint mismatch for $file: expected $expected, found $actual"
done <<'AARS'
lib-decoder-av1-release.aar 4caa18a79339cf5b0e7560b2c8068cffda478f01
lib-decoder-ffmpeg-release.aar 75ec0a868a99b87d3ee3e1ccd4d191c3a49560e4
lib-decoder-mpegh-release.aar e2b019207b80c838a4dbb73976d8120ff101d18f
lib-exoplayer-release.aar f55ad58cae1242d51036cf980a2332c50f620b50
lib-ui-release.aar 073f9b6be8e8cc2f95a6934dfff5c232f61451af
AARS

if grep -Fq 'StuckPlayerException' "$SOURCE_ROOT/PlaybackStallResume.android.kt"; then
    fail "PlaybackStallResume references a post-1.8.0 StuckPlayerException API."
fi
if grep -Eq 'ExperimentalApi|Flags\.' "$SOURCE_ROOT/PlaybackVideoScheduling.android.kt"; then
    fail "Playback scheduling references Media3 APIs introduced after 1.8.0."
fi
if grep -Fq 'ExperimentalApi::class' "$SOURCE_ROOT/PlayerEngine.android.kt"; then
    fail "PlayerEngine opts into an ExperimentalApi type absent from Media3 1.8.0."
fi
if grep -Eq 'Decoder\.Callback|setCallback\(|onInputBufferAvailable|onOutputBufferAvailable' "$SOURCE_ROOT/swdecoder/SoftwareVideoDecoder.kt"; then
    fail "SoftwareVideoDecoder must use Media3 1.8.0's polled Decoder interface, not callback APIs."
fi
for reader in BdavPgsReader.kt BdavTrueHdReader.kt; do
    grep -Fq 'override fun packetFinished(isEndOfInput: Boolean)' "$SOURCE_ROOT/bluray/$reader" \
        || fail "$reader does not implement the Media3 1.8.0 ElementaryStreamReader signature."
    if grep -Fq 'endOfInputReached' "$SOURCE_ROOT/bluray/$reader"; then
        fail "$reader still implements the post-1.8.0 endOfInputReached method."
    fi
done
grep -Fq 'override fun onPositionReset(positionUs: Long, joining: Boolean)' "$SOURCE_ROOT/seekpreview/SeekPreviewFrameSource.android.kt" \
    || fail "Seek preview does not implement the Media3 1.8.0 renderer reset signature."

printf 'PASS: Media3 1.8.0 catalog, minSdk 24, local AAR wiring/fingerprints and player API compatibility verified before Gradle.\n'
