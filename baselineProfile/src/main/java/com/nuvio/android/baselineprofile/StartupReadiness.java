package com.nuvio.android.baselineprofile;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiWindow;

/**
 * Shared cold-start readiness waits for Baseline Profile generation and
 * Macrobenchmark. A marker is accepted only when it belongs to the benchmark
 * package inside UI Automator's active window.
 *
 * This class never launches or re-foregrounds the target app. In particular,
 * a shell/terminal stealing focus during a measured launch is an environment
 * failure and must not be hidden by a second launch.
 */
final class StartupReadiness {
    static final String TARGET_APP_PACKAGE = "com.nuvio.media.benchmark";
    static final String HOME_MARKER = "nuvio_benchmark_home";
    static final String FULLY_DRAWN_MARKER = "nuvio_startup_ready";

    /** Cold launch + Home content can exceed 30s on supported mid-range phones. */
    static final long HOME_STARTUP_TIMEOUT_MS = 60_000L;
    /** Do not allow a shell-owned foreground window to contaminate the launch. */
    private static final long ACTIVE_WINDOW_TIMEOUT_MS = 15_000L;
    /** Once Home is visible, fail quickly if the product appReady signal is still missing. */
    static final long FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS = 15_000L;
    static final long HOME_BROWSE_MARKER_TIMEOUT_MS = 60_000L;
    private static final long POLL_MS = 250L;

    private StartupReadiness() {}

    static BySelector composeTagSelector(String marker) {
        // Compose testTagsAsResourceId exposes Modifier.testTag as a bare viewIdResourceName.
        // The package-qualified By.res(package, id) form formats "package:id/id"
        // and does not match the Compose tag. Scope the query to the target active window
        // and verify the matched node's application package in targetMarker() instead.
        return By.res(marker);
    }

    static String activeWindowPackage(UiDevice device) {
        try {
            UiWindow activeWindow = device.findWindow(By.Window.active(true));
            return activeWindow == null ? "<unavailable>" : String.valueOf(activeWindow.getPackageName());
        } catch (Exception ignored) {
            return "<unavailable>";
        }
    }

    static UiObject2 targetMarker(UiDevice device, String marker) {
        UiWindow activeWindow = device.findWindow(By.Window.active(true));
        if (activeWindow == null || !TARGET_APP_PACKAGE.equals(activeWindow.getPackageName())) {
            return null;
        }
        UiObject2 object = activeWindow.findObject(composeTagSelector(marker));
        if (object == null || !TARGET_APP_PACKAGE.equals(object.getApplicationPackage())) {
            return null;
        }
        return object;
    }

    static boolean markerVisible(UiDevice device, String marker) {
        return targetMarker(device, marker) != null;
    }

    static String diagnostics(UiDevice device) {
        String lastAccessibilityEventPackage = "<unavailable>";
        try {
            lastAccessibilityEventPackage = String.valueOf(device.getCurrentPackageName());
        } catch (Exception ignored) {
            // best-effort diagnostic only
        }
        return "\nActive window package: " + activeWindowPackage(device)
                + "\nLast package to report an accessibility event: " + lastAccessibilityEventPackage
                + "\nExpected app package: " + TARGET_APP_PACKAGE
                + "\nTarget package present anywhere in hierarchy (diagnostic only): "
                + safePackagePresence(device)
                + "\nAppGate main marker in target active window: " + safeMarkerState(device, "nuvio_appgate_main")
                + "\nAppGate profile-loading marker in target active window: "
                + safeMarkerState(device, "nuvio_appgate_profile_loading")
                + "\nMain content loading marker in target active window: "
                + safeMarkerState(device, "nuvio_main_content_loading")
                + "\nLaunch overlay transition marker in target active window: "
                + safeMarkerState(device, "nuvio_launch_overlay_transitioning")
                + "\nMain content ready marker in target active window: "
                + safeMarkerState(device, "nuvio_main_content_ready")
                + "\nMain content stateDescription: " + rootReadinessStateDescription(device)
                + "\nHomeScreen marker in target active window: " + safeMarkerState(device, HOME_MARKER)
                + "\nFully-drawn marker in target active window: "
                + safeMarkerState(device, FULLY_DRAWN_MARKER)
                + "\nNote: success requires the marker node itself to belong to the target package "
                + "in the active window; package presence elsewhere is diagnostic only.";
    }

    private static String safePackagePresence(UiDevice device) {
        try {
            return String.valueOf(device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null);
        } catch (RuntimeException error) {
            return "<query failed: " + error.getClass().getSimpleName() + ">";
        }
    }

    private static String safeMarkerState(UiDevice device, String marker) {
        try {
            return String.valueOf(markerVisible(device, marker));
        } catch (RuntimeException error) {
            return "<query failed: " + error.getClass().getSimpleName() + ">";
        }
    }

    private static String rootReadinessStateDescription(UiDevice device) {
        try {
            UiObject2 root = targetMarker(device, "nuvio_main_content_ready");
            if (root == null) {
                return "<main content ready marker not present in target active window>";
            }
            CharSequence state = root.getAccessibilityNodeInfo().getStateDescription();
            return state == null ? "<stateDescription unavailable>" : state.toString();
        } catch (Exception error) {
            return "<unavailable: " + error.getClass().getSimpleName() + ">";
        }
    }

    static boolean waitForTargetAppActive(UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (TARGET_APP_PACKAGE.equals(activeWindowPackage(device))) {
                return true;
            }
            SystemClock.sleep(POLL_MS);
        }
        return TARGET_APP_PACKAGE.equals(activeWindowPackage(device));
    }

    static boolean waitForMarker(UiDevice device, String marker, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (markerVisible(device, marker)) {
                return true;
            }
            SystemClock.sleep(POLL_MS);
        }
        return markerVisible(device, marker);
    }

    static void awaitHomeAndFullyDrawn(UiDevice device, String journeyName) {
        awaitHomeAndFullyDrawn(device, journeyName, Integer.valueOf(0));
    }

    static void awaitHomeAndFullyDrawn(
            UiDevice device, String journeyName, Integer measurementIteration) {
        long readinessStartedAt = SystemClock.elapsedRealtime();
        long activeWindowStartedAt = readinessStartedAt;
        long activeWindowWaitMs = 0L;
        long homeMarkerWaitMs = -1L;
        long fullyDrawnMarkerWaitMs = -1L;

        long homeDeadline = readinessStartedAt + HOME_STARTUP_TIMEOUT_MS;
        long activeWindowBudget = Math.min(ACTIVE_WINDOW_TIMEOUT_MS, HOME_STARTUP_TIMEOUT_MS);
        boolean targetAppActive = waitForTargetAppActive(device, activeWindowBudget);
        activeWindowWaitMs = SystemClock.elapsedRealtime() - activeWindowStartedAt;
        if (!targetAppActive) {
            reportReadinessTiming(
                    journeyName,
                    measurementIteration,
                    activeWindowWaitMs,
                    homeMarkerWaitMs,
                    fullyDrawnMarkerWaitMs,
                    SystemClock.elapsedRealtime() - readinessStartedAt,
                    "active_window_timeout");
            throw new AssertionError(
                    "The benchmark app is not the active foreground window. Refusing to relaunch "
                            + "it during a measured startup; leave " + TARGET_APP_PACKAGE
                            + " in front and retry." + diagnostics(device));
        }

        long remaining = homeDeadline - SystemClock.elapsedRealtime();
        boolean homeVisible;
        long homeMarkerStartedAt = SystemClock.elapsedRealtime();
        try {
            homeVisible = remaining > 0 && waitForMarker(device, HOME_MARKER, remaining);
        } catch (RuntimeException queryFailure) {
            homeMarkerWaitMs = SystemClock.elapsedRealtime() - homeMarkerStartedAt;
            reportReadinessTiming(
                    journeyName,
                    measurementIteration,
                    activeWindowWaitMs,
                    homeMarkerWaitMs,
                    fullyDrawnMarkerWaitMs,
                    SystemClock.elapsedRealtime() - readinessStartedAt,
                    "home_marker_query_failure");
            throw markerQueryFailure(HOME_MARKER, "HomeScreen", queryFailure, device);
        }
        homeMarkerWaitMs = SystemClock.elapsedRealtime() - homeMarkerStartedAt;
        if (!homeVisible) {
            reportReadinessTiming(
                    journeyName,
                    measurementIteration,
                    activeWindowWaitMs,
                    homeMarkerWaitMs,
                    fullyDrawnMarkerWaitMs,
                    SystemClock.elapsedRealtime() - readinessStartedAt,
                    "home_marker_timeout");
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker '" + HOME_MARKER
                            + "' within " + HOME_STARTUP_TIMEOUT_MS + " ms after launch."
                            + diagnostics(device));
        }

        boolean fullyDrawnVisible;
        long fullyDrawnMarkerStartedAt = SystemClock.elapsedRealtime();
        try {
            fullyDrawnVisible = waitForMarker(
                    device,
                    FULLY_DRAWN_MARKER,
                    FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS);
        } catch (RuntimeException queryFailure) {
            fullyDrawnMarkerWaitMs = SystemClock.elapsedRealtime() - fullyDrawnMarkerStartedAt;
            reportReadinessTiming(
                    journeyName,
                    measurementIteration,
                    activeWindowWaitMs,
                    homeMarkerWaitMs,
                    fullyDrawnMarkerWaitMs,
                    SystemClock.elapsedRealtime() - readinessStartedAt,
                    "fully_drawn_marker_query_failure");
            throw markerQueryFailure(FULLY_DRAWN_MARKER, "fully-drawn", queryFailure, device);
        }
        fullyDrawnMarkerWaitMs = SystemClock.elapsedRealtime() - fullyDrawnMarkerStartedAt;
        if (!fullyDrawnVisible) {
            reportReadinessTiming(
                    journeyName,
                    measurementIteration,
                    activeWindowWaitMs,
                    homeMarkerWaitMs,
                    fullyDrawnMarkerWaitMs,
                    SystemClock.elapsedRealtime() - readinessStartedAt,
                    "fully_drawn_marker_timeout");
            throw new AssertionError(
                    "HomeScreen is visible, but the product fully-drawn marker '"
                            + FULLY_DRAWN_MARKER + "' did not appear within "
                            + FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS
                            + " ms after Home. This is an app readiness failure, not a reason "
                            + "to restart the activity or extend the cold-start wait."
                            + diagnostics(device));
        }

        reportReadinessTiming(
                journeyName,
                measurementIteration,
                activeWindowWaitMs,
                homeMarkerWaitMs,
                fullyDrawnMarkerWaitMs,
                SystemClock.elapsedRealtime() - readinessStartedAt,
                "ready");
    }

    private static void reportReadinessTiming(
            String journeyName,
            Integer measurementIteration,
            long activeWindowWaitMs,
            long homeMarkerWaitMs,
            long fullyDrawnMarkerWaitMs,
            long totalWaitMs,
            String outcome) {
        if (measurementIteration == null && "ready".equals(outcome)) {
            // Macrobenchmark calls measureBlock for cold-start and compilation warm-ups too.
            // Only measured iterations contribute samples to the comparison summary.
            return;
        }
        String iterationLabel = measurementIteration == null
                ? "warmup"
                : String.valueOf(measurementIteration);
        Bundle status = new Bundle();
        status.putString(
                Instrumentation.REPORT_KEY_STREAMRESULT,
                "NUVIO_STARTUP_READINESS_TIMING: journey=" + journeyName
                        + " iteration=" + iterationLabel
                        + " active_window_wait_ms=" + activeWindowWaitMs
                        + " home_marker_wait_ms=" + homeMarkerWaitMs
                        + " fully_drawn_marker_wait_ms=" + fullyDrawnMarkerWaitMs
                        + " total_wait_ms=" + totalWaitMs
                        + " outcome=" + outcome);
        try {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        } catch (RuntimeException ignored) {
            // A diagnostic transport failure must not replace the readiness result.
        }
    }

    static AssertionError markerQueryFailure(
            String marker, String stage, RuntimeException cause, UiDevice device) {
        AssertionError failure = new AssertionError(
                "UI Automator threw while looking for the " + stage + " marker '"
                        + marker + "'. This is an accessibility query failure, not a marker timeout."
                        + diagnostics(device));
        failure.initCause(cause);
        return failure;
    }
}
