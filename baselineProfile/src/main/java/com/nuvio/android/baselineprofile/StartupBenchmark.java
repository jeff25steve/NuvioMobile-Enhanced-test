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
 * Run this manually on a physical device after generating and packaging the
 * profile. Set androidx.benchmark.enabledRules=macrobenchmark for the
 * instrumentation run on a physical device with a preconfigured signed-in
 * test profile. The measurement waits for Nuvio's real main-content-ready
 * marker so StartupTimingMetric can capture both TTID and TTFD. Auth-first
 * and onboarding launches intentionally fail this benchmark precondition.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupBenchmark {
    private static final String TARGET_PACKAGE_NAME = "com.nuvio.media";
    private static final int ITERATIONS = 10;
    private static final long FULLY_DRAWN_TIMEOUT_MS = 30_000L;

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

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
        boolean ready = device.wait(
                Until.hasObject(By.res("nuvio_startup_ready")),
                FULLY_DRAWN_TIMEOUT_MS
        );
        if (!ready) {
            throw new AssertionError(
                    "Nuvio did not reach main-content readiness within 30 seconds. " +
                    "Use a preconfigured signed-in test install; auth and onboarding states " +
                    "do not represent the interactive Home startup journey."
            );
        }
    }

    private void runColdStartupBenchmark(CompilationMode compilationMode) {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE_NAME,
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
