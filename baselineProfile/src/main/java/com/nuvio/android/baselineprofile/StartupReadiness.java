package com.nuvio.android.baselineprofile;

import android.os.SystemClock;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiWindow;

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
        // Compose testTagsAsResourceId maps Modifier.testTag to a bare resource name.
        // Keep the app package guard because UI Automator can see multiple windows/packages.
        return device.findObject(packageRes(marker)) != null
                && device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null;
    }

    static BySelector packageRes(String marker) {
        // Compose testTagsAsResourceId exposes Modifier.testTag as the bare resource name.
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

    static String diagnostics(UiDevice device) {
        String lastAccessibilityEventPackage = "<unavailable>";
        try {
            lastAccessibilityEventPackage = device.getCurrentPackageName();
        } catch (Exception ignored) {
            // best-effort
        }
        String activePackage = activeWindowPackage(device);
        return "\nActive window package: " + activePackage
                + "\nLast package to report an accessibility event: " + lastAccessibilityEventPackage
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
                + "\nMain content stateDescription: "
                + rootReadinessStateDescription(device)
                + "\nHomeScreen marker visible now: " + markerVisible(device, HOME_MARKER)
                + "\nFully-drawn marker visible now: " + markerVisible(device, FULLY_DRAWN_MARKER)
                + "\nNote: the last accessibility-event package is not a foreground indicator. "
                + "Use the active-window package above to identify the active UI window.";
    }


    private static String rootReadinessStateDescription(UiDevice device) {
        try {
            androidx.test.uiautomator.UiObject2 root =
                    device.findObject(By.res("nuvio_main_content_ready"));
            if (root == null) {
                return "<main content root not ready>";
            }
            // Compose stateDescription maps to AccessibilityNodeInfo.stateDescription,
            // not contentDescription. Read the correct accessibility property directly.
            CharSequence state = root.getAccessibilityNodeInfo().getStateDescription();
            return state == null ? "<stateDescription unavailable>" : state.toString();
        } catch (Exception error) {
            return "<unavailable: " + error.getClass().getSimpleName() + ">";
        }
    }

    private static boolean isKnownShellPackage(String packageName) {
        return packageName != null
                && !TARGET_APP_PACKAGE.equals(packageName)
                && (packageName.contains("ladb")
                        || packageName.contains("termux")
                        || packageName.contains("terminal")
                        || packageName.contains("shell"));
    }

    /**
     * Wait until the benchmark app appears in the hierarchy. Does not fail the
     * suite by itself; callers continue to marker waits with the remaining budget.
     */
    static void waitForTargetAppPresent(UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.findObject(By.pkg(TARGET_APP_PACKAGE)) != null) {
                return;
            }
            // Recover only when a known shell/terminal owns the active window.
            String current = activeWindowPackage(device);
            if (isKnownShellPackage(current)) {
                try {
                    device.executeShellCommand(
                            "monkey -p " + TARGET_APP_PACKAGE
                                    + " -c android.intent.category.LAUNCHER 1");
                } catch (Exception recoveryFailure) {
                    throw new IllegalStateException(
                            "Unable to restore Nuvio while the active window belongs to "
                                    + current + ".",
                            recoveryFailure);
                }
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
            // Recover only when a known shell/terminal owns the active window.
            String current = activeWindowPackage(device);
            if (isKnownShellPackage(current)) {
                try {
                    device.executeShellCommand(
                            "monkey -p " + TARGET_APP_PACKAGE
                                    + " -c android.intent.category.LAUNCHER 1");
                } catch (Exception recoveryFailure) {
                    throw new IllegalStateException(
                            "Unable to restore Nuvio while waiting for marker '"
                                    + marker + "'; active window package is " + current + ".",
                            recoveryFailure);
                }
            }
            SystemClock.sleep(POLL_MS);
        }
        return markerVisible(device, marker);
    }

    static void scrollRequired(
            UiDevice device,
            String marker,
            Direction direction,
            String journeyStep) {
        boolean markerPresent;
        try {
            markerPresent = waitForMarker(device, marker, READY_TIMEOUT_MS);
        } catch (RuntimeException queryFailure) {
            throw markerQueryFailure(marker, journeyStep, queryFailure, device);
        }
        if (!markerPresent) {
            throw new AssertionError(
                    "Home browse journey could not find the " + journeyStep
                            + " selector '" + marker + "' within " + READY_TIMEOUT_MS + " ms."
                            + diagnostics(device));
        }

        UiObject2 scrollable = device.findObject(packageRes(marker));
        if (scrollable == null || !scrollable.isScrollable()) {
            throw new AssertionError(
                    "Home browse journey found '" + marker
                            + "' but it is not an accessible scrollable " + journeyStep + "."
                            + diagnostics(device));
        }

        try {
            if (!scrollable.scroll(direction, 0.75f)) {
                throw new AssertionError(
                        "Home browse journey could not scroll " + journeyStep
                                + " in direction " + direction + "."
                                + diagnostics(device));
            }
        } catch (RuntimeException scrollFailure) {
            AssertionError failure = new AssertionError(
                    "UI Automator failed while scrolling " + journeyStep
                            + " in direction " + direction + "."
                            + diagnostics(device));
            failure.initCause(scrollFailure);
            throw failure;
        }
    }

    static void awaitHomeAndFullyDrawn(UiDevice device) {
        long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
        waitForTargetAppPresent(device, Math.min(15_000L, READY_TIMEOUT_MS));

        long remaining = deadline - SystemClock.elapsedRealtime();
        boolean homeVisible;
        try {
            homeVisible = remaining > 0 && waitForMarker(device, HOME_MARKER, remaining);
        } catch (RuntimeException queryFailure) {
            throw markerQueryFailure(HOME_MARKER, "HomeScreen", queryFailure, device);
        }
        if (!homeVisible) {
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker '" + HOME_MARKER
                            + "' within " + READY_TIMEOUT_MS + " ms."
                            + diagnostics(device));
        }

        remaining = deadline - SystemClock.elapsedRealtime();
        boolean fullyDrawnVisible;
        try {
            fullyDrawnVisible = remaining > 0
                    && waitForMarker(device, FULLY_DRAWN_MARKER, remaining);
        } catch (RuntimeException queryFailure) {
            throw markerQueryFailure(
                    FULLY_DRAWN_MARKER, "fully-drawn", queryFailure, device);
        }
        if (!fullyDrawnVisible) {
            throw new AssertionError(
                    "HomeScreen appeared, but Nuvio did not expose its fully-drawn marker '"
                            + FULLY_DRAWN_MARKER + "' before the "
                            + READY_TIMEOUT_MS + " ms startup deadline."
                            + diagnostics(device));
        }
    }

    private static AssertionError markerQueryFailure(
            String marker, String stage, RuntimeException cause, UiDevice device) {
        AssertionError failure = new AssertionError(
                "UI Automator threw while looking for the " + stage + " marker '"
                        + marker + "'. This is an accessibility query failure, not a marker timeout."
                        + diagnostics(device));
        failure.initCause(cause);
        return failure;
    }
}
