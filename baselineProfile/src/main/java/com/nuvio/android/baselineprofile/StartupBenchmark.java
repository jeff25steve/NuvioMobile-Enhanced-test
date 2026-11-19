package com.nuvio.android.baselineprofile;

import androidx.benchmark.ExperimentalConfig;
import androidx.benchmark.StartupInsightsConfig;
import androidx.benchmark.macro.ArtMetric;
import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Compares cold-start timing with no compilation, partial compilation
 * without a Baseline Profile, and the packaged Baseline Profile. All arms
 * share identical readiness and iteration settings.
 *
 * Run manually on a physical API 33+ device with the isolated
 * {@code com.nuvio.media.benchmark} install signed in on Home.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupBenchmark {
    private static final int ITERATIONS = 10;
    private static final ExperimentalConfig STARTUP_INSIGHTS_CONFIG =
            new ExperimentalConfig(null, new StartupInsightsConfig(true));

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Test
    public void startupNoCompilation() {
        runColdStartupBenchmark(
                "startupNoCompilation",
                CompilationMode.None.INSTANCE,
                java.util.Arrays.asList(new StartupTimingMetric(), new ArtMetric())
        );
    }

    @Test
    public void startupPartialWithoutBaselineProfile() {
        runColdStartupBenchmark(
                "startupPartialWithoutBaselineProfile",
                new CompilationMode.Partial(BaselineProfileMode.Disable, 3),
                java.util.Arrays.asList(new StartupTimingMetric(), new ArtMetric())
        );
    }

    @Test
    public void startupWithBaselineProfile() {
        runColdStartupBenchmark(
                "startupWithBaselineProfile",
                new CompilationMode.Partial(BaselineProfileMode.Require),
                java.util.Arrays.asList(new StartupTimingMetric(), new ArtMetric())
        );
    }

    private void runColdStartupBenchmark(
            String journeyName,
            CompilationMode compilationMode,
            java.util.List<androidx.benchmark.macro.Metric> metrics) {
        benchmarkRule.measureRepeated(
                StartupReadiness.TARGET_APP_PACKAGE,
                metrics,
                ITERATIONS,
                STARTUP_INSIGHTS_CONFIG,
                compilationMode,
                StartupMode.COLD,
                scope -> {
                    scope.pressHome();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    UiDevice device = UiDevice.getInstance(
                            InstrumentationRegistry.getInstrumentation());
                    StartupReadiness.awaitHomeAndFullyDrawn(device, journeyName, scope.getIteration());
                    return Unit.INSTANCE;
                }
        );
    }
}
