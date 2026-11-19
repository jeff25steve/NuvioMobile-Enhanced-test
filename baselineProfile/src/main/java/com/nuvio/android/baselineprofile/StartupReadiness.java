package com.nuvio.android.baselineprofile;

import android.os.SystemClock;

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

    static BySelector packageRes(String marker) {
        // By.res(resourcePackage, resourceId) matches the complete package:id/name resource.
        return By.res(TARGET_APP_PACKAGE, marker);
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
        UiObject2 object = activeWindow.findObject(packageRes(marker));
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

    static void awaitHomeAndFullyDrawn(UiDevice device) {
        long homeDeadline = SystemClock.elapsedRealtime() + HOME_STARTUP_TIMEOUT_MS;
        long activeWindowBudget = Math.min(ACTIVE_WINDOW_TIMEOUT_MS, HOME_STARTUP_TIMEOUT_MS);
        if (!waitForTargetAppActive(device, activeWindowBudget)) {
            throw new AssertionError(
                    "The benchmark app is not the active foreground window. Refusing to relaunch "
                            + "it during a measured startup; leave " + TARGET_APP_PACKAGE
                            + " in front and retry." + diagnostics(device));
        }

        long remaining = homeDeadline - SystemClock.elapsedRealtime();
        boolean homeVisible;
        try {
            homeVisible = remaining > 0 && waitForMarker(device, HOME_MARKER, remaining);
        } catch (RuntimeException queryFailure) {
            throw markerQueryFailure(HOME_MARKER, "HomeScreen", queryFailure, device);
        }
        if (!homeVisible) {
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker '" + HOME_MARKER
                            + "' within " + HOME_STARTUP_TIMEOUT_MS + " ms after launch."
                            + diagnostics(device));
        }

        boolean fullyDrawnVisible;
        try {
            fullyDrawnVisible = waitForMarker(
                    device,
                    FULLY_DRAWN_MARKER,
                    FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS);
        } catch (RuntimeException queryFailure) {
            throw markerQueryFailure(FULLY_DRAWN_MARKER, "fully-drawn", queryFailure, device);
        }
        if (!fullyDrawnVisible) {
            throw new AssertionError(
                    "HomeScreen is visible, but the product fully-drawn marker '"
                            + FULLY_DRAWN_MARKER + "' did not appear within "
                            + FULLY_DRAWN_AFTER_HOME_TIMEOUT_MS
                            + " ms after Home. This is an app readiness failure, not a reason "
                            + "to restart the activity or extend the cold-start wait."
                            + diagnostics(device));
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
