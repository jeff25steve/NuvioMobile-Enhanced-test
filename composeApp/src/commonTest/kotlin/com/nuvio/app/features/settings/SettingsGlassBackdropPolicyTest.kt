package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.shouldCaptureLiquidGlassSettingsBackdrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsGlassBackdropPolicyTest {
    @Test
    fun nativeProfileRouteCapturesOnlyTheProfileWatchTimeSurface() {
        assertEquals(
            setOf(LiquidGlassSurface.PROFILE_WATCH_TIME),
            settingsAdditionalBackdropSurfacesForPage(
                initialPageName = SettingsPage.Profile.name,
                usesRouteBasedNavigation = true,
            ),
        )
    }

    @Test
    fun nativeSupportersRouteCapturesOnlyTheMembershipSurface() {
        val expected = if (AppFeaturePolicy.supportersContributorsPageEnabled) {
            setOf(LiquidGlassSurface.SUPPORTER_MEMBERSHIP)
        } else {
            emptySet()
        }
        assertEquals(
            expected,
            settingsAdditionalBackdropSurfacesForPage(
                initialPageName = SettingsPage.SupportersContributors.name,
                usesRouteBasedNavigation = true,
            ),
        )
    }

    @Test
    fun unrelatedNativeRoutesDoNotCaptureDefaultAdditionalSurfaces() {
        val additionalSurfaces = settingsAdditionalBackdropSurfacesForPage(
            initialPageName = SettingsPage.PlaybackPlayer.name,
            usesRouteBasedNavigation = true,
        )

        assertTrue(additionalSurfaces.isEmpty())
        assertFalse(
            shouldCaptureLiquidGlassSettingsBackdrop(
                masterEnabled = true,
                isSurfacePreferenceEnabled = { it.defaultEnabled },
                additionalSurfaces = additionalSurfaces,
            ),
        )
    }

    @Test
    fun internalPageNavigationRetainsTheExistingConservativeCaptureSet() {
        assertEquals(
            setOf(
                LiquidGlassSurface.PROFILE_WATCH_TIME,
                LiquidGlassSurface.SUPPORTER_MEMBERSHIP,
            ),
            settingsAdditionalBackdropSurfacesForPage(
                initialPageName = SettingsPage.Root.name,
                usesRouteBasedNavigation = false,
            ),
        )
    }

    @Test
    fun theGeneralSettingsSurfaceStillRequestsBackdropOnAnyRouteWhenEnabled() {
        val additionalSurfaces = settingsAdditionalBackdropSurfacesForPage(
            initialPageName = SettingsPage.PlaybackPlayer.name,
            usesRouteBasedNavigation = true,
        )

        assertTrue(
            shouldCaptureLiquidGlassSettingsBackdrop(
                masterEnabled = true,
                isSurfacePreferenceEnabled = { it == LiquidGlassSurface.SETTINGS },
                additionalSurfaces = additionalSurfaces,
            ),
        )
    }
}
