package com.nuvio.app.core.ui

import com.nuvio.app.appOneHandedModeController
import com.nuvio.app.features.home.components.HomeHeroTrailerPlaybackController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneHandedNativeTabIntegrationTest {

    @Test
    fun nativeTabToggleUsesTheSharedInMemoryController() {
        resetSharedState()
        try {
            nativeTabToggleOneHandedMode()
            assertTrue(appOneHandedModeController.state.value.enabled)

            nativeTabToggleOneHandedMode()
            assertFalse(appOneHandedModeController.state.value.enabled)
        } finally {
            resetSharedState()
        }
    }

    @Test
    fun nativeTabToggleStopsInlineTrailerAndEnablesMode() {
        resetSharedState()
        var stopCount = 0
        val stopCallback: () -> Unit = { stopCount++ }
        HomeHeroTrailerPlaybackController.register(stopCallback)
        try {
            HomeHeroTrailerPlaybackController.setInlineTrailerPlaybackActive(true)

            nativeTabToggleOneHandedMode()

            assertFalse(HomeHeroTrailerPlaybackController.isInlineTrailerPlaybackActive())
            assertEquals(1, stopCount)
            assertTrue(appOneHandedModeController.state.value.enabled)
        } finally {
            HomeHeroTrailerPlaybackController.unregister(stopCallback)
            resetSharedState()
        }
    }

    private fun resetSharedState() {
        HomeHeroTrailerPlaybackController.setInlineTrailerPlaybackActive(false)
        appOneHandedModeController.disableForPlayerEntry()
    }
}
