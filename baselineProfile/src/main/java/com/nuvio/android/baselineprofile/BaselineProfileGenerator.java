package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import android.os.SystemClock;


import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Collects only the real launcher startup path for the release application.
 *
 * Run this manually on a supported physical API 33+ device. The generated
 * rules are committed only after review; this test never runs as part of a
 * normal release build.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class BaselineProfileGenerator {
    private static final long MAIN_CONTENT_READY_TIMEOUT_MS = 30_000L;

    // The test module is self-instrumenting, so InstrumentationRegistry's target
    // context is the test APK itself, not the app being measured. Both distribution
    // flavors deliberately share this isolated benchmark application ID.
    private static final String TARGET_APP_PACKAGE = "com.nuvio.media.benchmark";

    private static String targetPackageName() {
        return TARGET_APP_PACKAGE;
    }

    private static boolean markerVisible(UiDevice device, String marker) {
        try {
            return device.findObject(By.res(marker)) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readinessDiagnostics(UiDevice device) {
        String lastAccessibilityEventPackage = "<unavailable>";
        try {
            // UiDevice documents this as the last package to report an accessibility event,
            // not necessarily the package currently in the foreground.
            lastAccessibilityEventPackage = device.getCurrentPackageName();
        } catch (Exception ignored) {
            // Keep diagnostics best-effort; never replace the original readiness assertion.
        }
        return "\nLast package to report an accessibility event: " + lastAccessibilityEventPackage +
                "\nExpected app package: " + TARGET_APP_PACKAGE +
                "\nHomeScreen marker visible now: " + markerVisible(device, "nuvio_benchmark_home") +
                "\nFully-drawn marker visible now: " + markerVisible(device, "nuvio_startup_ready") +
                "\nAccessibility hierarchy omitted: Android's XML dumper can throw on invalid " +
                "characters in accessibility text, which must not mask the original benchmark failure.";
    }

    private static void awaitMainContentReady() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        long deadline = SystemClock.elapsedRealtime() + MAIN_CONTENT_READY_TIMEOUT_MS;
        boolean homeVisible = device.wait(
                Until.hasObject(By.res("nuvio_benchmark_home")),
                MAIN_CONTENT_READY_TIMEOUT_MS
        );
        if (!homeVisible) {
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker 'nuvio_benchmark_home' within 30 seconds." +
                    readinessDiagnostics(device)
            );
        }

        long remainingMs = deadline - SystemClock.elapsedRealtime();
        boolean fullyDrawn = remainingMs > 0 && device.wait(
                Until.hasObject(By.res("nuvio_startup_ready")),
                remainingMs
        );
        if (!fullyDrawn) {
            throw new AssertionError(
                    "HomeScreen appeared, but Nuvio did not expose its fully-drawn marker " +
                    "'nuvio_startup_ready' before the 30-second startup deadline." +
                    readinessDiagnostics(device)
            );
        }
    }

    @Rule
    public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    @Test
    public void startup() {
        baselineProfileRule.collect(
                targetPackageName(),
                15,
                3,
                "startup",
                true,
                true,
                rule -> true,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    awaitMainContentReady();
                    return Unit.INSTANCE;
                }
        );
    }
}
