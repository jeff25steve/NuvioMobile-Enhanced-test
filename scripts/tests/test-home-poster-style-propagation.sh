#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
HOME_CARD="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomePosterCard.kt"
SHELF="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/ShelfComponents.kt"
HOME_ROW="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeCatalogSection.kt"
DETAIL_RAIL="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailPosterRailSection.kt"

for source in "$HOME_CARD" "$SHELF" "$HOME_ROW" "$DETAIL_RAIL"; do
  [ -f "$source" ] || {
    echo "FAIL: missing poster performance source: $source" >&2
    exit 1
  }
done

# The shared Home row/rail snapshot must flow through both composables so
# visible and LazyRow-prefetched cards don't each subscribe to the same StateFlow.
grep -Fq 'posterCardStyle: PosterCardStyleUiState? = null' "$HOME_CARD" || {
  echo "FAIL: HomePosterCard must accept a caller-provided poster style snapshot." >&2
  exit 1
}
grep -Fq 'val resolvedPosterCardStyle = posterCardStyle ?: rememberPosterCardStyleUiState()' "$HOME_CARD" || {
  echo "FAIL: standalone HomePosterCard callers must retain the repository-backed fallback." >&2
  exit 1
}
grep -Fq 'posterCardStyle = resolvedPosterCardStyle' "$HOME_CARD" || {
  echo "FAIL: HomePosterCard must pass its resolved snapshot to NuvioPosterCard." >&2
  exit 1
}
grep -Fq 'posterCardStyle: PosterCardStyleUiState? = null' "$SHELF" || {
  echo "FAIL: NuvioPosterCard must accept a caller-provided poster style snapshot." >&2
  exit 1
}
grep -Fq 'val resolvedPosterCardStyle = posterCardStyle ?: rememberPosterCardStyleUiState()' "$SHELF" || {
  echo "FAIL: standalone NuvioPosterCard callers must retain the repository-backed fallback." >&2
  exit 1
}
grep -Fq 'posterCardStyle = posterCardStyle' "$HOME_ROW" || {
  echo "FAIL: Home catalog rows must pass their existing shared style snapshot to HomePosterCard." >&2
  exit 1
}
grep -Fq 'posterCardStyle = posterCardStyle' "$DETAIL_RAIL" || {
  echo "FAIL: detail poster rails must pass their existing shared style snapshot to HomePosterCard." >&2
  exit 1
}

printf 'PASS: Home and detail poster cards reuse the parent style snapshot instead of adding per-card StateFlow collectors.\n'
