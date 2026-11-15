package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import android.os.SystemClock;
import androidx.test.filters.LargeTest;

import java.util.Collections;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Compares cold-start time with no AOT compilation against a required,
 * packaged Nuvio Baseline Profile.
 *
 * Run manually on a physical API 33+ device. The test target package is
 * resolved from the installed app variant, so the isolated .benchmark
 * application ID is used without touching the production installation.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupBenchmark {
    private static final int ITERATIONS = 10;
    private static final long FULLY_DRAWN_TIMEOUT_MS = 30_000L;

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

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
                "\nAppGate main marker visible now: " + markerVisible(device, "nuvio_appgate_main") +
                "\nAppGate profile-loading marker visible now: " + markerVisible(device, "nuvio_appgate_profile_loading") +
                "\nAppGate current gate marker: " +
                    (markerVisible(device, "nuvio_appgate_loading") ? "loading" :
                     markerVisible(device, "nuvio_appgate_auth") ? "auth" :
                     markerVisible(device, "nuvio_appgate_profileselection") ? "profile selection" :
                     markerVisible(device, "nuvio_appgate_profileedit") ? "profile edit" : "not visible") +
                "\nMain content loading marker visible now: " + markerVisible(device, "nuvio_main_content_loading") +
                "\nLaunch overlay transition marker visible now: " + markerVisible(device, "nuvio_launch_overlay_transitioning") +
                "\nMain content ready marker visible now: " + markerVisible(device, "nuvio_main_content_ready") +
                "\nReadiness home-ready/profile-idle: " + markerVisible(device, "nuvio_readiness_home_ready_profile_switch_idle") +
                "\nReadiness home-ready/profile-loading: " + markerVisible(device, "nuvio_readiness_home_ready_profile_switch_loading") +
                "\nReadiness home-pending/profile-idle: " + markerVisible(device, "nuvio_readiness_home_pending_profile_switch_idle") +
                "\nReadiness home-pending/profile-loading: " + markerVisible(device, "nuvio_readiness_home_pending_profile_switch_loading") +
                "\nHomeScreen marker visible now: " + markerVisible(device, "nuvio_benchmark_home") +
                "\nFully-drawn marker visible now: " + markerVisible(device, "nuvio_startup_ready") +
                "\nAccessibility hierarchy omitted: Android's XML dumper can throw on invalid " +
                "characters in accessibility text, which must not mask the original benchmark failure.";
    }

    private static void awaitMainContentReady() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        long deadline = SystemClock.elapsedRealtime() + FULLY_DRAWN_TIMEOUT_MS;
        boolean homeVisible = device.wait(
                Until.hasObject(By.res("nuvio_benchmark_home")),
                FULLY_DRAWN_TIMEOUT_MS
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

    private void runColdStartupBenchmark(CompilationMode compilationMode) {
        benchmarkRule.measureRepeated(
                targetPackageName(),
                Collections.singletonList(new StartupTimingMetric()),
                compilationMode,
                StartupMode.COLD,
                ITERATIONS,
                scope -> {
                    scope.pressHome();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    awaitMainContentReady();
                    return Unit.INSTANCE;
                }
        );
    }
}
