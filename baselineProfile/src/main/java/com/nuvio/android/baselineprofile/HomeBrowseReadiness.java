package com.nuvio.android.baselineprofile;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObject2Ext;

/**
 * UI Automator actions for the Home browse Baseline Profile journey.
 * Kept separate from startup waits so marker ownership and timing policy
 * remain centralized in StartupReadiness.
 */
final class HomeBrowseReadiness {
    private static final float SCROLL_PERCENT = 0.75f;
    private static final long MARKER_POLL_INTERVAL_MS = 100L;

    private HomeBrowseReadiness() {}

    static void scrollUntilMarker(
            UiDevice device,
            String scrollMarker,
            String targetMarker,
            Direction direction,
            String journeyStep) {
        boolean sourcePresent;
        try {
            sourcePresent = StartupReadiness.waitForMarker(
                    device, scrollMarker, StartupReadiness.HOME_BROWSE_MARKER_TIMEOUT_MS);
        } catch (RuntimeException queryFailure) {
            throw StartupReadiness.markerQueryFailure(scrollMarker, journeyStep, queryFailure, device);
        }
        if (!sourcePresent) {
            throw new AssertionError(
                    "Home browse journey could not find the " + journeyStep
                            + " scroll container '" + scrollMarker + "'."
                            + StartupReadiness.diagnostics(device));
        }

        UiObject2 scrollable = requireScrollable(device, scrollMarker, journeyStep);
        try {
            // AndroidX 2.4.1 polls the accessibility subtree while scrolling, rather
            // than limiting long Home feeds to an arbitrary number of gestures.
            UiObject2Ext.scrollToElement(
                    scrollable,
                    direction,
                    StartupReadiness.HOME_BROWSE_MARKER_TIMEOUT_MS,
                    MARKER_POLL_INTERVAL_MS,
                    node -> isVisibleTargetMarker(node, targetMarker));
        } catch (RuntimeException scrollFailure) {
            throw scrollFailure(scrollFailure, direction, journeyStep, device);
        }

        // The subtree predicate is not a substitute for our stricter active-window,
        // target-package and clipped-visible-bounds checks.
        if (!markerHasVisibleBounds(device, targetMarker)) {
            throw new AssertionError(
                    "Home browse journey found selector '" + targetMarker
                            + "' while scrolling " + journeyStep + " " + direction
                            + ", but it is not visibly exposed in the target active window."
                            + StartupReadiness.diagnostics(device));
        }
    }

    static void scrollRequired(
            UiDevice device,
            String marker,
            Direction direction,
            String journeyStep) {
        boolean markerPresent;
        try {
            markerPresent = StartupReadiness.waitForMarker(
                    device, marker, StartupReadiness.HOME_BROWSE_MARKER_TIMEOUT_MS);
        } catch (RuntimeException queryFailure) {
            throw StartupReadiness.markerQueryFailure(marker, journeyStep, queryFailure, device);
        }
        if (!markerPresent) {
            throw new AssertionError(
                    "Home browse journey could not find the " + journeyStep
                            + " selector '" + marker + "' within "
                            + StartupReadiness.HOME_BROWSE_MARKER_TIMEOUT_MS + " ms."
                            + StartupReadiness.diagnostics(device));
        }

        UiObject2 scrollable = requireScrollable(device, marker, journeyStep);
        try {
            // false means the requested direction reached a scroll boundary; it is not an error.
            scrollable.scroll(direction, SCROLL_PERCENT);
        } catch (RuntimeException scrollFailure) {
            throw scrollFailure(scrollFailure, direction, journeyStep, device);
        }
    }

    private static boolean isVisibleTargetMarker(
            AccessibilityNodeInfo node, String targetMarker) {
        if (!node.isVisibleToUser()) return false;
        String resourceName = node.getViewIdResourceName();
        return targetMarker.equals(resourceName)
                || (resourceName != null && resourceName.endsWith("/" + targetMarker));
    }

    private static UiObject2 requireScrollable(
            UiDevice device, String marker, String journeyStep) {
        UiObject2 scrollable = StartupReadiness.targetMarker(device, marker);
        if (scrollable == null || !scrollable.isScrollable()) {
            throw new AssertionError(
                    "Home browse journey found '" + marker
                            + "' in the target active window, but it is not a scrollable "
                            + journeyStep + "." + StartupReadiness.diagnostics(device));
        }
        return scrollable;
    }

    private static boolean markerHasVisibleBounds(UiDevice device, String marker) {
        UiObject2 object = StartupReadiness.targetMarker(device, marker);
        if (object == null) return false;
        Rect bounds = object.getVisibleBounds();
        return bounds.width() > 0 && bounds.height() > 0;
    }

    private static AssertionError scrollFailure(
            RuntimeException cause, Direction direction, String journeyStep, UiDevice device) {
        AssertionError failure = new AssertionError(
                "UI Automator failed while scrolling " + journeyStep + " in direction "
                        + direction + "." + StartupReadiness.diagnostics(device));
        failure.initCause(cause);
        return failure;
    }
}
