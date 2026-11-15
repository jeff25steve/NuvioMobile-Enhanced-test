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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

    private static String readinessDiagnostics(UiDevice device) {
        String foregroundPackage = "<unavailable>";
        try {
            foregroundPackage = device.getCurrentPackageName();
            ByteArrayOutputStream hierarchyOutput = new ByteArrayOutputStream();
            device.dumpWindowHierarchy(hierarchyOutput);
            String hierarchy = new String(
                    hierarchyOutput.toByteArray(),
                    StandardCharsets.UTF_8
            ).replaceAll("(text|content-desc)=\"[^\"]*\"", "$1=\"[redacted]\"");
            final int maxHierarchyChars = 8_000;
            if (hierarchy.length() > maxHierarchyChars) {
                hierarchy = hierarchy.substring(0, maxHierarchyChars) + "\n...[truncated]";
            }
            return "\nForeground package: " + foregroundPackage +
                    "\nExpected package: " + TARGET_APP_PACKAGE +
                    "\nWindow hierarchy (text and content descriptions redacted):\n" + hierarchy;
        } catch (Exception error) {
            return "\nForeground package: " + foregroundPackage +
                    "\nCould not capture the accessibility hierarchy: " +
                    error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    @Test
    public void coldStartupWithoutBaselineProfile() {
        runColdStartupBenchmark(new CompilationMode.None());
    }

    @Test
    public void coldStartupWithRequiredBaselineProfile() {
        runColdStartupBenchmark(
                new CompilationMode.Partial(BaselineProfileMode.Require)
        );
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
