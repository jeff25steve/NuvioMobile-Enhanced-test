package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
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
 * instrumentation run. The app's current startup path does not explicitly
 * report fully-drawn state, so TTID is the guaranteed comparison metric;
 * TTFD requires a separate app readiness signal.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class StartupBenchmark {
    private static final String TARGET_PACKAGE_NAME = "com.nuvio.media";
    private static final int ITERATIONS = 10;

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
                    return Unit.INSTANCE;
                }
        );
    }
}
