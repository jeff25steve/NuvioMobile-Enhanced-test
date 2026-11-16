package com.nuvio.android.baselineprofile;

import androidx.benchmark.ExperimentalConfig;
import androidx.benchmark.StartupInsightsConfig;
import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import android.os.SystemClock;
import androidx.test.filters.LargeTest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Compares the same cold-start journey under no AOT compilation and a required,
 * packaged Nuvio Baseline Profile.
 *
 * The eight parameterized blocks use two ABBA rounds (A/B/B/A, A/B/B/A), with
 * three cold starts per block. This gives twelve samples per compilation mode
 * while balancing which mode runs before and after the other. Every block is a
 * separate Macrobenchmark measurement so AndroidX applies its requested
 * compilation mode before that block.
 *
 * Run on a physical API 33+ device. The target package is isolated from the
 * production installation and remains the same across both distribution flavors.
 */
@RunWith(Parameterized.class)
@LargeTest
public final class StartupBenchmark {
    private static final int ITERATIONS_PER_BLOCK = 3;
    private static final int BLOCK_COUNT = 8;
    private static final int SAMPLES_PER_MODE = 12;
    private static final long FULLY_DRAWN_TIMEOUT_MS = 30_000L;

    // Analyze the same Perfetto traces used for timing so common startup blockers
    // are visible in the instrumentation output without changing the measured app.
    private static final ExperimentalConfig STARTUP_INSIGHTS_CONFIG =
            new ExperimentalConfig(null, new StartupInsightsConfig(true));

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    // The test module is self-instrumenting, so InstrumentationRegistry's target
    // context is the test APK itself, not the app being measured. Both distribution
    // flavors deliberately share this isolated benchmark application ID.
    private static final String TARGET_APP_PACKAGE = "com.nuvio.media.benchmark";

    private final String blockLabel;
    private final CompilationMode compilationMode;

    public StartupBenchmark(String blockLabel, CompilationMode compilationMode) {
        this.blockLabel = blockLabel;
        this.compilationMode = compilationMode;
    }

    /**
     * Ordered parameter list, matching the A/B/B/A design used by Sentry's
     * production Macrobenchmark comparison. Repeating ABBA makes each mode the
     * trailing arm equally often without increasing the total sample count much.
     */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> comparisonBlocks() {
        return Arrays.<Object[]>asList(
                new Object[] {"01-none-a", new CompilationMode.None()},
                new Object[] {"02-profile-b", new CompilationMode.Partial(BaselineProfileMode.Require)},
                new Object[] {"03-profile-b", new CompilationMode.Partial(BaselineProfileMode.Require)},
                new Object[] {"04-none-a", new CompilationMode.None()},
                new Object[] {"05-none-a", new CompilationMode.None()},
                new Object[] {"06-profile-b", new CompilationMode.Partial(BaselineProfileMode.Require)},
                new Object[] {"07-profile-b", new CompilationMode.Partial(BaselineProfileMode.Require)},
                new Object[] {"08-none-a", new CompilationMode.None()}
        );
    }

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

    @Test
    public void startup() {
        runColdStartupBenchmark(compilationMode);
    }

    private void runColdStartupBenchmark(CompilationMode mode) {
        benchmarkRule.measureRepeated(
                targetPackageName(),
                Collections.singletonList(new StartupTimingMetric()),
                ITERATIONS_PER_BLOCK,
                STARTUP_INSIGHTS_CONFIG,
                mode,
                StartupMode.COLD,
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
