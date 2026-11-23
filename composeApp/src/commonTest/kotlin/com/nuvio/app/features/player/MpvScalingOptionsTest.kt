package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvScalingOptionsTest {
    @Test
    fun defaultFiltersFollowBalancedProfile() {
        assertEquals(
            mapOf(
                "scale" to "lanczos",
                "dscale" to "hermite",
                "cscale" to "lanczos",
            ),
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.Balanced,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
            ),
        )
    }

    @Test
    fun defaultFiltersFollowHighQualityProfile() {
        assertEquals(
            mapOf(
                "scale" to "ewa_lanczossharp",
                "dscale" to "hermite",
                "cscale" to "ewa_lanczossharp",
            ),
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.HighQuality,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
            ),
        )
    }

    @Test
    fun defaultFiltersFollowPerformanceProfile() {
        assertEquals(
            mapOf(
                "scale" to "bilinear",
                "dscale" to "bilinear",
                "cscale" to "bilinear",
            ),
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.Performance,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
            ),
        )
    }

    @Test
    fun eachExplicitFilterOverridesOnlyItsOwnProfileAxis() {
        assertEquals(
            mapOf(
                "scale" to "spline36",
                "dscale" to "mitchell",
                "cscale" to "ewa_lanczos",
            ),
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.HighQuality,
                AndroidLibmpvScalingFilter.Spline36,
                AndroidLibmpvScalingFilter.Mitchell,
                AndroidLibmpvScalingFilter.EwaLanczos,
            ),
        )
    }

    @Test
    fun chromaDefaultFollowsExplicitUpscaler() {
        assertEquals(
            "catmull_rom",
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.Balanced,
                AndroidLibmpvScalingFilter.CatmullRom,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
            ).getValue("cscale"),
        )
    }

    @Test
    fun switchingQualityPreservesExplicitFiltersAndResolvesDefaultsAgain() {
        assertEquals(
            mapOf(
                "scale" to "spline36",
                "dscale" to "bilinear",
                "cscale" to "spline36",
            ),
            mpvScalingOptionValues(
                AndroidLibmpvRenderQuality.Performance,
                AndroidLibmpvScalingFilter.Spline36,
                AndroidLibmpvScalingFilter.Default,
                AndroidLibmpvScalingFilter.Default,
            ),
        )
    }
}
