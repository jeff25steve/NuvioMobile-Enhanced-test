#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
SCHEDULER="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/AndroidDownloadScheduler.kt"
LOCK="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/KeyedMutex.kt"
TEST="$ROOT_DIR/composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/downloads/KeyedMutexTest.kt"

for source in "$SCHEDULER" "$LOCK" "$TEST"; do
  [ -s "$source" ] || {
    echo "FAIL: required keyed-lock source or test is missing: $source" >&2
    exit 1
  }
done

grep -Fq 'private val locks = KeyedMutex()' "$SCHEDULER" || {
  echo "FAIL: download scheduler does not use the reference-counted keyed mutex." >&2
  exit 1
}
grep -Fq 'locks.withLock(fileName)' "$SCHEDULER" || {
  echo "FAIL: removal cleanup is not serialized by the per-file lock." >&2
  exit 1
}
grep -Fq 'locks.withLock(transfer.item.fileName)' "$SCHEDULER" || {
  echo "FAIL: transfer execution is not serialized by the per-file lock." >&2
  exit 1
}
if grep -Eq 'ConcurrentHashMap<String, Mutex>|private fun lock\(fileName: String\)' "$SCHEDULER"; then
  echo "FAIL: scheduler retains the unbounded per-file Mutex registry." >&2
  exit 1
fi
grep -Fq 'if (entry.users == 0 && entries[key] === entry)' "$LOCK" || {
  echo "FAIL: keyed mutex does not remove only the unused matching entry." >&2
  exit 1
}
grep -Fq 'entry.users++' "$LOCK" || {
  echo "FAIL: keyed mutex does not retain references for holders and waiters." >&2
  exit 1
}
grep -Fq 'entry.users--' "$LOCK" || {
  echo "FAIL: keyed mutex does not release references after completion or cancellation." >&2
  exit 1
}
grep -Fq 'internal fun trackedReferenceCount(key: String): Int' "$LOCK" || {
  echo "FAIL: keyed-lock regression tests cannot observe active holder/waiter references." >&2
  exit 1
}
grep -Fq 'awaitTrackedReferences(keyedMutex, "same", 2)' "$TEST" || {
  echo "FAIL: same-key regression does not prove a waiter has registered before asserting serialization." >&2
  exit 1
}
grep -Fq 'assertEquals(1, keyedMutex.trackedReferenceCount("same")' "$TEST" || {
  echo "FAIL: cancellation regression does not verify that the queued waiter's reference was released." >&2
  exit 1
}
for case in sameKeyIsSerializedAndEntryIsReleasedAfterUse differentKeysCanEnterConcurrently cancelledWaiterReleasesItsKeyReference; do
  grep -Fq "fun $case()" "$TEST" || {
    echo "FAIL: missing keyed-lock concurrency regression: $case." >&2
    exit 1
  }
done

printf 'PASS: download execution and cleanup share bounded per-file locks; regression tests assert real holder/waiter references.\n'
