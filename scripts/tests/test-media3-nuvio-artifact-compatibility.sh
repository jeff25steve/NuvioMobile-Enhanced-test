#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
VERSION_CATALOG="$ROOT_DIR/gradle/libs.versions.toml"
BUILD_GRADLE="$ROOT_DIR/composeApp/build.gradle.kts"
PLAYER_SRC="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/player"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

check_contains() {
  file="$1"
  needle="$2"
  grep -Fq -- "$needle" "$file" || fail "Expected '$needle' in $file"
}

check_absent() {
  file="$1"
  needle="$2"
  if grep -Fq -- "$needle" "$file"; then
    fail "Unsupported Media3 API '$needle' remains in $file"
  fi
}

check_blob() {
  expected="$1"
  relative_path="$2"
  file="$ROOT_DIR/$relative_path"
  [ -f "$file" ] || fail "Missing Nuvio Media3 artifact: $relative_path"
  actual="$(git -C "$ROOT_DIR" hash-object "$file")"
  [ "$actual" = "$expected" ] || fail "$relative_path fingerprint mismatch (expected $expected, got $actual)"
}

grep -Eq '^[[:space:]]*androidx-media3[[:space:]]*=[[:space:]]*"1\.8\.0"[[:space:]]*$' "$VERSION_CATALOG" ||
  fail "Media3 catalog must remain pinned to 1.8.0 with the current Nuvio AAR set"

min_sdk="$(sed -n 's/^[[:space:]]*android-minSdk[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\)".*/\1/p' "$VERSION_CATALOG" | head -n 1)"
[ -n "$min_sdk" ] && [ "$min_sdk" -ge 24 ] || fail "Android minSdk must remain at least 24"

awk '
  /module[[:space:]]*=[[:space:]]*"androidx\.media3:/ {
    if ($0 !~ /version\.ref[[:space:]]*=[[:space:]]*"androidx-media3"/) {
      print "Unpinned Media3 dependency: " $0 > "/dev/stderr"
      failed = 1
    }
  }
  END { exit failed }
' "$VERSION_CATALOG" || fail "Every androidx.media3 module must use the shared androidx-media3 version alias"

check_contains "$BUILD_GRADLE" 'fileTree("libs")'
check_contains "$BUILD_GRADLE" 'include("lib-*.aar")'
check_contains "$BUILD_GRADLE" 'exclude(group = "androidx.media3", module = "media3-exoplayer")'
check_contains "$BUILD_GRADLE" 'exclude(group = "androidx.media3", module = "media3-ui")'

check_blob "4caa18a79339cf5b0e7560b2c8068cffda478f01" "composeApp/libs/lib-decoder-av1-release.aar"
check_blob "75ec0a868a99b87d3ee3e1ccd4d191c3a49560e4" "composeApp/libs/lib-decoder-ffmpeg-release.aar"
check_blob "e2b019207b80c838a4dbb73976d8120ff101d18f" "composeApp/libs/lib-decoder-mpegh-release.aar"
check_blob "f55ad58cae1242d51036cf980a2332c50f620b50" "composeApp/libs/lib-exoplayer-release.aar"
check_blob "073f9b6be8e8cc2f95a6934dfff5c232f61451af" "composeApp/libs/lib-ui-release.aar"

check_contains "$PLAYER_SRC/PlaybackStallResume.android.kt" 'getField("stuckType")'
check_absent "$PLAYER_SRC/PlaybackStallResume.android.kt" 'import androidx.media3.common.util.StuckPlayerException'
check_contains "$PLAYER_SRC/PlaybackVideoScheduling.android.kt" 'internal fun enableMedia3VideoRendererDurationToProgress() = Unit'
check_absent "$PLAYER_SRC/PlaybackVideoScheduling.android.kt" 'import androidx.media3.common.Flags'
check_absent "$PLAYER_SRC/PlaybackVideoScheduling.android.kt" 'Flags.enableFlag'
check_absent "$PLAYER_SRC/PlaybackVideoScheduling.android.kt" 'ExperimentalApi'

check_contains "$PLAYER_SRC/bluray/BdavPgsReader.kt" 'packetFinished(isEndOfInput: Boolean)'
check_absent "$PLAYER_SRC/bluray/BdavPgsReader.kt" 'endOfInputReached()'
check_contains "$PLAYER_SRC/bluray/BdavTrueHdReader.kt" 'packetFinished(isEndOfInput: Boolean)'
check_absent "$PLAYER_SRC/bluray/BdavTrueHdReader.kt" 'endOfInputReached()'

check_absent "$PLAYER_SRC/swdecoder/SoftwareVideoDecoder.kt" 'Decoder.Callback'
check_absent "$PLAYER_SRC/swdecoder/SoftwareVideoDecoder.kt" 'setCallback('
check_absent "$PLAYER_SRC/swdecoder/SoftwareVideoDecoder.kt" 'dispatchCallback'

check_contains "$PLAYER_SRC/seekpreview/SeekPreviewFrameSource.android.kt" 'super.onPositionReset(positionUs, joining)'
check_absent "$PLAYER_SRC/seekpreview/SeekPreviewFrameSource.android.kt" 'sampleStreamIsResetToKeyFrame'
check_contains "$PLAYER_SRC/DisplayAwareDefaultTrackSelector.kt" 'invalidate()'
check_absent "$PLAYER_SRC/DisplayAwareDefaultTrackSelector.kt" 'invalidate(null)'
check_contains "$PLAYER_SRC/LivePlayback.android.kt" 'override fun getAllocator(): Allocator'
check_absent "$PLAYER_SRC/LivePlayback.android.kt" 'getAllocator(playerId)'
check_absent "$PLAYER_SRC/LivePlayback.android.kt" 'shouldContinuePreloading(playerId'
check_contains "$PLAYER_SRC/PlaybackBuffering.android.kt" '.setBufferDurationsMs('
check_absent "$PLAYER_SRC/PlaybackBuffering.android.kt" 'setBufferDurationsMsForLocalPlayback'
check_absent "$PLAYER_SRC/PlaybackBuffering.android.kt" 'setBufferDurationsMsForStreaming'

check_absent "$PLAYER_SRC/PlayerEngine.android.kt" 'ExperimentalApi'
check_absent "$PLAYER_SRC/PlayerEngine.android.kt" 'getDecoderInfosSortedByFormatSupport(context,'
check_contains "$PLAYER_SRC/PlayerEngine.android.kt" 'getDecoderInfosSortedByFormatSupport(decoderInfos, format)'

check_contains "$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncLibassSubtitles.kt" 'import androidx.media3.common.util.Consumer'
check_absent "$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutoSyncLibassSubtitles.kt" 'java.util.function.Consumer'
check_contains "$PLAYER_SRC/aiostreams/AiostreamsAssEngine.kt" 'import androidx.media3.common.util.Consumer'
check_absent "$PLAYER_SRC/aiostreams/AiostreamsAssEngine.kt" 'java.util.function.Consumer'
check_contains "$PLAYER_SRC/aiostreams/Subtitles.kt" 'import androidx.media3.common.util.Consumer'
check_absent "$PLAYER_SRC/aiostreams/Subtitles.kt" 'java.util.function.Consumer'

echo "PASS: Media3 1.8.0 catalog and all five Nuvio AAR fingerprints match the last known successful run #51."
echo "PASS: Android player sources use the pinned Media3 1.8.0 API surface."
