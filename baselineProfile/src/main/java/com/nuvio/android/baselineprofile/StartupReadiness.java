package com.nuvio.android.baselineprofile;

import android.os.SystemClock;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

/**
 * Shared cold-start readiness waits for Baseline Profile generation and
 * Macrobenchmark. Selectors are scoped to the benchmark application id so a
 * foreground on-device terminal (e.g. LADB) does not satisfy or poison checks.
 */
final class StartupReadiness {
    static final String TARGET_APP_PACKAGE = "com.nuvio.media.benchmark";
    static final String HOME_MARKER = "nuvio_benchmark_home";
    static final String FULLY_DRAWN_MARKER = "nuvio_startup_ready";

    /** Cold start + Home + overlays on mid-range phones often exceeds 30s. */
    static final long READY_TIMEOUT_MS = 60_000L;
    private static final long POLL_MS = 250L;

    private StartupReadiness() {}

    static boolean markerVisible(UiDevice device, String marker) {
        try {
            if (device.findObject(packageRes(marker)) != null) {
                return true;
            }
            // Some Compose/accessibility bridges only expose the bare testTag.
            // Accept that form only while the target app is present in the hierarchy.
            return device.findObject(By.res(marker)) != null
                    && device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static BySelector packageRes(String marker) {
        return By.res(TARGET_APP_PACKAGE, marker);
    }

    static String diagnostics(UiDevice device) {
        String lastAccessibilityEventPackage = "<unavailable>";
        try {
            lastAccessibilityEventPackage = device.getCurrentPackageName();
        } catch (Exception ignored) {
            // best-effort
        }
        return "\nLast package to report an accessibility event: " + lastAccessibilityEventPackage
                + "\nExpected app package: " + TARGET_APP_PACKAGE
                + "\nTarget package present in hierarchy: "
                + (device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null)
                + "\nAppGate main marker visible now: " + markerVisible(device, "nuvio_appgate_main")
                + "\nAppGate profile-loading marker visible now: "
                + markerVisible(device, "nuvio_appgate_profile_loading")
                + "\nMain content loading marker visible now: "
                + markerVisible(device, "nuvio_main_content_loading")
                + "\nLaunch overlay transition marker visible now: "
                + markerVisible(device, "nuvio_launch_overlay_transitioning")
                + "\nMain content ready marker visible now: "
                + markerVisible(device, "nuvio_main_content_ready")
                + "\nHomeScreen marker visible now: " + markerVisible(device, HOME_MARKER)
                + "\nFully-drawn marker visible now: " + markerVisible(device, FULLY_DRAWN_MARKER)
                + "\nNote: if the last accessibility package is an on-device shell (e.g. com.draco.ladb),"
                + " leave that app immediately after starting the test so Nuvio stays in front.";
    }

    /**
     * Wait until the benchmark app appears in the hierarchy. Does not fail the
     * suite by itself; callers continue to marker waits with the remaining budget.
     */
    static void waitForTargetAppPresent(UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null) {
                    return;
                }
                // Nudge the target only if a shell/terminal still owns the session.
                String current = device.getCurrentPackageName();
                if (current != null
                        && !TARGET_APP_PACKAGE.equals(current)
                        && (current.contains("ladb")
                                || current.contains("termux")
                                || current.contains("terminal")
                                || current.contains("shell"))) {
                    device.executeShellCommand(
                            "monkey -p " + TARGET_APP_PACKAGE
                                    + " -c android.intent.category.LAUNCHER 1");
                }
            } catch (Exception ignored) {
                // keep polling
            }
            SystemClock.sleep(POLL_MS);
        }
    }

    static boolean waitForMarker(UiDevice device, String marker, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (markerVisible(device, marker)) {
                return true;
            }
            // If a terminal stole the foreground mid-wait, bring Nuvio back and continue.
            try {
                String current = device.getCurrentPackageName();
                if (current != null
                        && !TARGET_APP_PACKAGE.equals(current)
                        && (current.contains("ladb")
                                || current.contains("termux")
                                || current.contains("terminal")
                                || current.contains("shell"))) {
                    device.executeShellCommand(
                            "monkey -p " + TARGET_APP_PACKAGE
                                    + " -c android.intent.category.LAUNCHER 1");
                }
            } catch (Exception ignored) {
                // keep polling
            }
            SystemClock.sleep(POLL_MS);
        }
        return markerVisible(device, marker);
    }

    static void awaitHomeAndFullyDrawn(UiDevice device) {
        long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
        waitForTargetAppPresent(device, Math.min(15_000L, READY_TIMEOUT_MS));

        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || !waitForMarker(device, HOME_MARKER, remaining)) {
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker '" + HOME_MARKER
                            + "' within " + READY_TIMEOUT_MS + " ms."
                            + diagnostics(device));
        }

        remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || !waitForMarker(device, FULLY_DRAWN_MARKER, remaining)) {
            throw new AssertionError(
                    "HomeScreen appeared, but Nuvio did not expose its fully-drawn marker '"
                            + FULLY_DRAWN_MARKER + "' before the "
                            + READY_TIMEOUT_MS + " ms startup deadline."
                            + diagnostics(device));
        }
    }
}
