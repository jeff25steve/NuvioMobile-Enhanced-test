package com.nuvio.android.baselineprofile;

import androidx.test.uiautomator.StaleObjectException;

/**
 * Retries only read/query operations when UI Automator invalidates an accessibility node.
 * Callers must reacquire nodes inside the operation; never wrap gestures or other side effects.
 */
final class StaleNodeRetry {
    static final int MAX_ATTEMPTS = 3;

    @FunctionalInterface
    interface Query<T> {
        T get();
    }

    private StaleNodeRetry() {}

    static <T> T run(Query<T> query) {
        StaleObjectException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return query.get();
            } catch (StaleObjectException stale) {
                lastFailure = stale;
            }
        }
        throw lastFailure;
    }
}
