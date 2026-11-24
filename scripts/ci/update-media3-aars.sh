#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_REPOSITORY="moneytoo/Player"
readonly SOURCE_COMMIT="6df39ae74464ec8b9cb8bb3992ee3ab508513a11"
readonly SOURCE_BASE="https://raw.githubusercontent.com/${SOURCE_REPOSITORY}/${SOURCE_COMMIT}/app/libs"
readonly TARGET_DIR="composeApp/libs"
readonly EXPECTED_BRANCH="ci/main-build-testing-20261124"

if [[ "${GITHUB_REF_NAME:-}" != "$EXPECTED_BRANCH" ]]; then
  echo "Refusing to sync Media3 AARs outside $EXPECTED_BRANCH." >&2
  exit 1
fi

cd "${GITHUB_WORKSPACE:-$(pwd)}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
  --connect-timeout 15 --max-time 90 \
  "$SOURCE_BASE/../build.gradle" -o "$tmp_dir/upstream-build.gradle"
grep -Fq "1.12.0-beta01" "$tmp_dir/upstream-build.gradle" || {
  echo "Pinned upstream source does not declare Media3 1.12.0-beta01." >&2
  exit 1
}

mkdir -p "$TARGET_DIR"
while IFS=' ' read -r file expected_blob_sha; do
  [[ -n "$file" ]] || continue
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
    --connect-timeout 15 --max-time 240 \
    "$SOURCE_BASE/$file" -o "$tmp_dir/$file"
  actual_blob_sha="$(git hash-object "$tmp_dir/$file")"
  if [[ "$actual_blob_sha" != "$expected_blob_sha" ]]; then
    echo "Unexpected upstream blob for $file: $actual_blob_sha" >&2
    exit 1
  fi
  install -m 0644 "$tmp_dir/$file" "$TARGET_DIR/$file"
  printf 'Verified %s (%s)\n' "$file" "$actual_blob_sha"
done <<'AARS'
lib-decoder-av1-release.aar b0ea68d9e0cdd46bc167a343dcfa94732a3adfb8
lib-decoder-ffmpeg-release.aar b8ee68452bad7fd895657c5f3a9840d566622946
lib-decoder-mpegh-release.aar e808e5f6bf44301f184aacb114888f65508bc1ef
lib-exoplayer-release.aar 61377b0160ca93af7113e8e2cf42eb535c9c00b6
lib-ui-release.aar 32f2a8f52e4f0b7ecbffb3dc41e2da39b29eb0da
AARS

git add -- \
  composeApp/libs/lib-decoder-av1-release.aar \
  composeApp/libs/lib-decoder-ffmpeg-release.aar \
  composeApp/libs/lib-decoder-mpegh-release.aar \
  composeApp/libs/lib-exoplayer-release.aar \
  composeApp/libs/lib-ui-release.aar

if git diff --cached --quiet; then
  echo "Pinned Media3 AARs are already current."
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git commit -m "Update bundled Media3 AARs to 1.12.0-beta01"
git push origin "HEAD:$EXPECTED_BRANCH"
