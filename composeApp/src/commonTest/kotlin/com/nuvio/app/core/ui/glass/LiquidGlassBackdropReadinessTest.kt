package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidGlassBackdropReadinessTest {
    @Test
    fun missingBackdropAlwaysUsesFallback() {
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                backdropPresent = false,
                platformSupported = true,
                hostReady = true,
            ),
        )
    }

    @Test
    fun unpositionedHostBackdropUsesFallbackUntilItsSourceIsReady() {
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                backdropPresent = true,
                platformSupported = true,
                hostReady = false,
            ),
        )
    }

    @Test
    fun readyBackdropUsesRendererOnlyOnSupportedPlatforms() {
        assertTrue(
            shouldUseLiquidGlassBackdrop(
                backdropPresent = true,
                platformSupported = true,
                hostReady = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                backdropPresent = true,
                platformSupported = false,
                hostReady = true,
            ),
        )
    }
}
