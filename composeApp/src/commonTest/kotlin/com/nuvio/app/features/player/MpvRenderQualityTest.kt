package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvRenderQualityTest {
    @Test
    fun balancedUsesMpvsNativeQualityPerformanceDefaults() {
        assertEquals(null, AndroidLibmpvRenderQuality.Balanced.profileName)
    }

    @Test
    fun qualityPresetsMapOnlyToUpstreamBuiltInProfiles() {
        assertEquals("fast", AndroidLibmpvRenderQuality.Performance.profileName)
        assertEquals("high-quality", AndroidLibmpvRenderQuality.HighQuality.profileName)
    }

    @Test
    fun switchingProfilesRestoresPreviousOverridesBeforeApplyingNext() {
        assertEquals(
            listOf(
                MpvRenderProfileAction(profileName = "fast", restore = true),
                MpvRenderProfileAction(profileName = "high-quality", restore = false),
            ),
            mpvRenderProfileTransition(
                AndroidLibmpvRenderQuality.Performance,
                AndroidLibmpvRenderQuality.HighQuality,
            ),
        )
        assertEquals(
            listOf(MpvRenderProfileAction(profileName = "high-quality", restore = true)),
            mpvRenderProfileTransition(
                AndroidLibmpvRenderQuality.HighQuality,
                AndroidLibmpvRenderQuality.Balanced,
            ),
        )
    }

    @Test
    fun selectingSameProfileDoesNotIssueCommands() {
        assertEquals(
            emptyList(),
            mpvRenderProfileTransition(
                AndroidLibmpvRenderQuality.HighQuality,
                AndroidLibmpvRenderQuality.HighQuality,
            ),
        )
    }

    @Test
    fun balancedIsTheDefaultForNewPlayerSettings() {
        assertEquals(
            AndroidLibmpvRenderQuality.Balanced,
            PlayerSettingsUiState().androidLibmpvRenderQuality,
        )
    }

    @Test
    fun switchingFromEitherOverrideBackToBalancedRestoresThatProfile() {
        assertEquals(
            listOf(MpvRenderProfileAction(profileName = "fast", restore = true)),
            mpvRenderProfileTransition(
                AndroidLibmpvRenderQuality.Performance,
                AndroidLibmpvRenderQuality.Balanced,
            ),
        )
        assertEquals(
            listOf(MpvRenderProfileAction(profileName = "high-quality", restore = true)),
            mpvRenderProfileTransition(
                AndroidLibmpvRenderQuality.HighQuality,
                AndroidLibmpvRenderQuality.Balanced,
            ),
        )
    }

    @Test
    fun debandingIsOffByDefault() {
        assertEquals(false, PlayerSettingsUiState().androidLibmpvDebandEnabled)
    }

}
