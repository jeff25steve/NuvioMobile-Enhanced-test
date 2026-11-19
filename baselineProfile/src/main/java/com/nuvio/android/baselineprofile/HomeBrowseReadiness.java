package com.nuvio.android.baselineprofile;

import android.graphics.Rect;

import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

/**
 * UI Automator actions for the Home browse Baseline Profile journey.
 * Kept separate from startup waits so marker ownership and timing policy
 * remain centralized in StartupReadiness.
 */
final class HomeBrowseReadiness {
    private static final int MAX_SCROLL_ATTEMPTS = 6;
    private static final float SCROLL_PERCENT = 0.75f;

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

        for (int attempt = 0; attempt < MAX_SCROLL_ATTEMPTS; attempt++) {
            if (markerHasVisibleBounds(device, targetMarker)) {
                return;
            }
            UiObject2 scrollable = requireScrollable(device, scrollMarker, journeyStep);
            try {
                if (!scrollable.scroll(direction, SCROLL_PERCENT)) {
                    break;
                }
            } catch (RuntimeException scrollFailure) {
                throw scrollFailure(scrollFailure, direction, journeyStep, device);
            }
        }
        if (markerHasVisibleBounds(device, targetMarker)) {
            return;
        }

        throw new AssertionError(
                "Home browse journey could not reveal selector '" + targetMarker
                        + "' by scrolling " + journeyStep + " " + direction + "."
                        + StartupReadiness.diagnostics(device));
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
