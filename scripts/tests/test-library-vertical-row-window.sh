#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
MODEL="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryDisplaySettings.kt"
CONTENT="$ROOT_DIR/composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibrarySavedContent.kt"

for source in "$MODEL" "$CONTENT"; do
  [ -f "$source" ] || {
    echo "FAIL: missing Library vertical-row source: $source" >&2
    exit 1
  }
done

grep -Fq 'fun rowCount(columns: Int)' "$MODEL" || {
  echo "FAIL: Library projection must expose an indexed row count." >&2
  exit 1
}
grep -Fq 'fun rowAt(index: Int, columns: Int)' "$MODEL" || {
  echo "FAIL: Library projection must expose indexed row access." >&2
  exit 1
}
grep -Fq 'return entries.subList(start, minOf(start + columns, entries.size))' "$MODEL" || {
  echo "FAIL: row access must return a bounded view without materializing all row lists." >&2
  exit 1
}
grep -Fq 'count = projection.rowCount(columns)' "$CONTENT" || {
  echo "FAIL: vertical Library LazyColumn must use indexed row count." >&2
  exit 1
}
grep -Fq 'projection.rowAt(rowIndex, columns)' "$CONTENT" || {
  echo "FAIL: vertical Library LazyColumn must resolve rows on demand." >&2
  exit 1
}
if grep -Fq 'projection.entries.chunked(columns)' "$CONTENT"; then
  echo "FAIL: vertical Library content must not eagerly chunk the entire projection." >&2
  exit 1
fi
grep -Fq '"library-vertical:${firstEntry.item.type}:${firstEntry.item.id}"' "$CONTENT" || {
  echo "FAIL: existing first-entry stable row-key identity must be retained." >&2
  exit 1
}

printf 'PASS: vertical Library rows are indexed on demand without eager chunked-list allocation.\n'
