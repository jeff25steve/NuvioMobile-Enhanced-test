package com.nuvio.app.core.ui

import com.nuvio.app.appOneHandedModeController
import com.nuvio.app.features.home.components.HomeHeroTrailerPlaybackController
import kotlin.test.Test
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
    fun nativeTabToggleIsBlockedWhileInlineTrailerPlaybackIsActive() {
        resetSharedState()
        try {
            HomeHeroTrailerPlaybackController.setInlineTrailerPlaybackActive(true)

            nativeTabToggleOneHandedMode()

            assertFalse(appOneHandedModeController.state.value.enabled)
        } finally {
            resetSharedState()
        }
    }

    private fun resetSharedState() {
        HomeHeroTrailerPlaybackController.setInlineTrailerPlaybackActive(false)
        appOneHandedModeController.disableForPlayerEntry()
    }
}
