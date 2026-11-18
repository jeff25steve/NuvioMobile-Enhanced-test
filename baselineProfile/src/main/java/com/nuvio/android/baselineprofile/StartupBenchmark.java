package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import java.util.Collections;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Compares cold-start time with no AOT compilation against a required,
 * packaged Nuvio Baseline Profile.
 *
 * Run manually on a physical API 33+ device with the isolated
 * {@code com.nuvio.media.benchmark} install signed in on Home.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupBenchmark {
    private static final int ITERATIONS = 10;

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Test
    public void startupCompilationNone() {
        runColdStartupBenchmark(CompilationMode.None.INSTANCE);
    }

    @Test
    public void startupCompilationBaselineProfile() {
        runColdStartupBenchmark(
                new CompilationMode.Partial(BaselineProfileMode.Require)
        );
    }

    private void runColdStartupBenchmark(CompilationMode compilationMode) {
        benchmarkRule.measureRepeated(
                StartupReadiness.TARGET_APP_PACKAGE,
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
                    UiDevice device = UiDevice.getInstance(
                            InstrumentationRegistry.getInstrumentation());
                    StartupReadiness.awaitHomeAndFullyDrawn(device);
                    return Unit.INSTANCE;
                }
        );
    }
}
