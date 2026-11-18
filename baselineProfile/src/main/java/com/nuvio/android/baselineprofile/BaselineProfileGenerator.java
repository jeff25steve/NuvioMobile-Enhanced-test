package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


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

    private static String captureForegroundStateAtFailure() {
        String command =
                "echo 'Resumed activity:'; " +
                "dumpsys activity activities 2>&1 | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 8; " +
                "echo 'Focused window:'; " +
                "dumpsys window windows 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4; " +
                "echo 'Benchmark process PID:'; " +
                "pidof com.nuvio.media.benchmark 2>&1";
        try {
            ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .executeShellCommand(command);
            if (descriptor == null) {
                return "<unavailable: shell command returned no output descriptor>";
            }
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                String result = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
                return result.isEmpty() ? "<empty shell output>" : result;
            }
        } catch (Exception error) {
            return "<unavailable: " + error.getClass().getSimpleName() + ": " +
                    String.valueOf(error.getMessage()) + ">";
        }
    }

    private static String readinessDiagnostics(
            UiDevice device,
            String waitPhase,
            long homeWaitElapsedMs,
            long remainingMs,
            long fullyDrawnWaitElapsedMs
    ) {
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
                "\nReadiness wait phase: " + waitPhase +
                "\nHomeScreen wait elapsed: " + homeWaitElapsedMs + " ms" +
                "\nRemaining shared deadline when fully-drawn wait began: " + remainingMs + " ms" +
                "\nFully-drawn wait elapsed: " +
                    (fullyDrawnWaitElapsedMs < 0 ? "not started" : fullyDrawnWaitElapsedMs + " ms") +
                "\nResumed activity and focused window at assertion (failure-only shell snapshot):\n" +
                    captureForegroundStateAtFailure() +
                "\nAccessibility hierarchy omitted: Android's XML dumper can throw on invalid " +
                "characters in accessibility text, which must not mask the original benchmark failure.";
    }

    private static void awaitMainContentReady() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        long waitStartedAt = SystemClock.elapsedRealtime();
        long deadline = waitStartedAt + MAIN_CONTENT_READY_TIMEOUT_MS;
        boolean homeVisible = device.wait(
                Until.hasObject(By.res("nuvio_benchmark_home")),
                MAIN_CONTENT_READY_TIMEOUT_MS
        );
        long homeWaitElapsedMs = SystemClock.elapsedRealtime() - waitStartedAt;
        if (!homeVisible) {
            throw new AssertionError(
                    "Nuvio did not expose the HomeScreen marker 'nuvio_benchmark_home' within 30 seconds." +
                    readinessDiagnostics(
                            device,
                            "HomeScreen",
                            homeWaitElapsedMs,
                            deadline - SystemClock.elapsedRealtime(),
                            -1
                    )
            );
        }

        long remainingMs = deadline - SystemClock.elapsedRealtime();
        long fullyDrawnWaitStartedAt = SystemClock.elapsedRealtime();
        boolean fullyDrawn = remainingMs > 0 && device.wait(
                Until.hasObject(By.res("nuvio_startup_ready")),
                remainingMs
        );
        long fullyDrawnWaitElapsedMs = SystemClock.elapsedRealtime() - fullyDrawnWaitStartedAt;
        if (!fullyDrawn) {
            throw new AssertionError(
                    "HomeScreen appeared, but Nuvio did not expose its fully-drawn marker " +
                    "'nuvio_startup_ready' before the 30-second startup deadline." +
                    readinessDiagnostics(
                            device,
                            "fully-drawn",
                            homeWaitElapsedMs,
                            remainingMs,
                            fullyDrawnWaitElapsedMs
                    )
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
