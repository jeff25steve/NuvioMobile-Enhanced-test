package com.nuvio.app.core.ui

import com.nuvio.app.appOneHandedModeController
import com.nuvio.app.OneHandedModePlacement
import com.nuvio.app.OneHandedModeState
import com.nuvio.app.features.home.components.HomeHeroTrailerPlaybackController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneHandedNativeTabIntegrationTest {

    @Test
    fun resetSharedStateRestoresAllEphemeralPresentationValues() {
        resetSharedState()
        appOneHandedModeController.toggle()
        appOneHandedModeController.togglePlacement()
        appOneHandedModeController.updateScaleMultiplier(1.2f)
        appOneHandedModeController.updateVerticalPosition(0.7f)

        resetSharedState()

        assertEquals(OneHandedModeState(), appOneHandedModeController.state.value)
    }

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
        if (appOneHandedModeController.state.value.placement != OneHandedModePlacement.Right) {
            appOneHandedModeController.togglePlacement()
        }
        appOneHandedModeController.updateScaleMultiplier(1f)
        appOneHandedModeController.updateVerticalPosition(0f)
        appOneHandedModeController.disableForPlayerEntry()
    }
}
