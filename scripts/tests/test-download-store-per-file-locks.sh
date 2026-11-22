#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
STORE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/AndroidDownloadStore.kt"
LOCK="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/KeyedSynchronizedLock.kt"
TEST="$ROOT_DIR/composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/downloads/AndroidDownloadStoreTest.kt"

for source in "$STORE" "$LOCK" "$TEST"; do
  [ -s "$source" ] || {
    echo "FAIL: required download-store source or regression test is missing: $source" >&2
    exit 1
  }
done

grep -Fq 'private val locks = KeyedSynchronizedLock()' "$STORE" || {
  echo "FAIL: download store does not use bounded per-file synchronization." >&2
  exit 1
}
if grep -Fq 'private val lock = Any()' "$STORE" || grep -Fq 'synchronized(lock)' "$STORE"; then
  echo "FAIL: download store still serializes every file through one global monitor." >&2
  exit 1
fi
for operation in 'locks.withLock(item.fileName)' 'locks.withLock(fileName)'; do
  grep -Fq "$operation" "$STORE" || {
    echo "FAIL: missing per-file synchronization boundary: $operation" >&2
    exit 1
  }
done
grep -Fq 'stateFlow.update { current -> current + (transfer.item.fileName to transfer) }' "$STORE" || {
  echo "FAIL: persisted transfer publication is not an atomic StateFlow map update." >&2
  exit 1
}
grep -Fq 'stateFlow.update { current -> current - fileName }' "$STORE" || {
  echo "FAIL: transfer removal is not an atomic StateFlow map update." >&2
  exit 1
}
grep -Fq 'private fun allocateJobId(): Int = synchronized(jobIdLock)' "$STORE" || {
  echo "FAIL: job ID allocation is not independently serialized across file keys." >&2
  exit 1
}
grep -Fq 'if (entry.users == 0 && entries[key] === entry)' "$LOCK" || {
  echo "FAIL: idle per-file lock entries are not safely released." >&2
  exit 1
}
grep -Fq 'updatesForDifferentFilesDoNotBlockEachOther' "$TEST" || {
  echo "FAIL: missing cross-file concurrency regression test." >&2
  exit 1
}
grep -Fq 'concurrentBeginsAllocateDistinctJobIds' "$TEST" || {
  echo "FAIL: missing concurrent job ID allocation regression test." >&2
  exit 1
}
grep -Fq 'updatesForTheSameFileRemainSerialized' "$TEST" || {
  echo "FAIL: missing same-file serialization regression test." >&2
  exit 1
}

printf 'PASS: download-store persistence is serialized per file, shared state updates are atomic, and lock entries are released.\n'
