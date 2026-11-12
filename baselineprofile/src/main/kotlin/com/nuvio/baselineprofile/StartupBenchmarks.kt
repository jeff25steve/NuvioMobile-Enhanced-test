package com.nuvio.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compares cold-start performance without ahead-of-time compilation and with the
 * app's shipped Baseline Profile. Run on a physical device, not an emulator.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutBaselineProfile() = benchmark(CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = benchmark(
        CompilationMode.Partial(BaselineProfileMode.Require),
    )

    private fun benchmark(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = targetPackageName(),
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    private fun targetPackageName(): String =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("Missing targetAppId instrumentation argument; verify the tested benchmark variant")

    private companion object {
        const val ITERATIONS = 10
    }
}
