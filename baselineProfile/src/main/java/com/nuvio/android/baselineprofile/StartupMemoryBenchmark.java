package com.nuvio.android.baselineprofile;

import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.benchmark.ExperimentalConfig;
import androidx.benchmark.MemoryProfilingConfig;
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
 * Captures ART and native heap allocations during a dedicated cold Home launch.
 *
 * AndroidX Benchmark runs the heap profiler in one additional profiling pass
 * after the ordinary measured iteration. This class is intentionally separate
 * from StartupBenchmark so heap-profiling trace outputs can never contaminate
 * the clean ten-iteration startup comparison.
 *
 * Run manually on a physical API 33+ device with the isolated
 * {@code com.nuvio.media.benchmark} install signed in on Home.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupMemoryBenchmark {
    private static final int ITERATIONS = 1;

    private static final ExperimentalConfig MEMORY_PROFILING_CONFIG =
            new ExperimentalConfig(
                    null,
                    new StartupInsightsConfig(true),
                    new MemoryProfilingConfig(true, true)
            );

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Test
    public void startupWithBaselineProfileMemoryProfile() {
        benchmarkRule.measureRepeated(
                StartupReadiness.TARGET_APP_PACKAGE,
                java.util.Arrays.asList(new StartupTimingMetric(), new ArtMetric()),
                ITERATIONS,
                MEMORY_PROFILING_CONFIG,
                new CompilationMode.Partial(BaselineProfileMode.Require),
                StartupMode.COLD,
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
