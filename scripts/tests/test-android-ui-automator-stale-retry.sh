#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
RETRY_SOURCE="$ROOT_DIR/baselineProfile/src/main/java/com/nuvio/android/baselineprofile/StaleNodeRetry.java"

[ -f "$RETRY_SOURCE" ] || {
  echo "FAIL: stale-node retry helper is missing: $RETRY_SOURCE" >&2
  exit 1
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
mkdir -p "$TMP_DIR/src/androidx/test/uiautomator" "$TMP_DIR/src/com/nuvio/android/baselineprofile" "$TMP_DIR/classes"

cat > "$TMP_DIR/src/androidx/test/uiautomator/StaleObjectException.java" <<'JAVA'
package androidx.test.uiautomator;

public final class StaleObjectException extends RuntimeException {
    public StaleObjectException(String message) {
        super(message);
    }
}
JAVA

cat > "$TMP_DIR/src/com/nuvio/android/baselineprofile/StaleNodeRetryTest.java" <<'JAVA'
package com.nuvio.android.baselineprofile;

import androidx.test.uiautomator.StaleObjectException;

public final class StaleNodeRetryTest {
    private StaleNodeRetryTest() {}

    public static void main(String[] args) {
        returnsImmediatelyWhenTheFirstLookupSucceeds();
        reacquiresAfterTransientStaleNodes();
        rethrowsTheLastStaleExceptionAfterTheRetryLimit();
        doesNotRetryUnrelatedRuntimeFailures();
        System.out.println("PASS: stale-node retry behavior");
    }

    private static void returnsImmediatelyWhenTheFirstLookupSucceeds() {
        int[] calls = {0};
        String result = StaleNodeRetry.run(() -> {
            calls[0]++;
            return "fresh";
        });
        assertEquals("fresh", result, "first-attempt result");
        assertEquals(1, calls[0], "first-attempt call count");
    }

    private static void reacquiresAfterTransientStaleNodes() {
        int[] calls = {0};
        String result = StaleNodeRetry.run(() -> {
            calls[0]++;
            if (calls[0] < 3) {
                throw new StaleObjectException("stale-" + calls[0]);
            }
            return "reacquired";
        });
        assertEquals("reacquired", result, "recovered result");
        assertEquals(3, calls[0], "recovery call count");
    }

    private static void rethrowsTheLastStaleExceptionAfterTheRetryLimit() {
        int[] calls = {0};
        try {
            StaleNodeRetry.run(() -> {
                calls[0]++;
                throw new StaleObjectException("stale-" + calls[0]);
            });
            throw new AssertionError("Expected StaleObjectException after retry limit");
        } catch (StaleObjectException expected) {
            assertEquals("stale-" + StaleNodeRetry.MAX_ATTEMPTS, expected.getMessage(),
                    "last stale exception");
            assertEquals(StaleNodeRetry.MAX_ATTEMPTS, calls[0], "retry limit");
        }
    }

    private static void doesNotRetryUnrelatedRuntimeFailures() {
        int[] calls = {0};
        try {
            StaleNodeRetry.run(() -> {
                calls[0]++;
                throw new IllegalArgumentException("not a stale node");
            });
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("not a stale node", expected.getMessage(), "unrelated failure");
            assertEquals(1, calls[0], "unrelated failure call count");
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
JAVA

javac -d "$TMP_DIR/classes" \
  "$TMP_DIR/src/androidx/test/uiautomator/StaleObjectException.java" \
  "$RETRY_SOURCE" \
  "$TMP_DIR/src/com/nuvio/android/baselineprofile/StaleNodeRetryTest.java"
java -cp "$TMP_DIR/classes" com.nuvio.android.baselineprofile.StaleNodeRetryTest
