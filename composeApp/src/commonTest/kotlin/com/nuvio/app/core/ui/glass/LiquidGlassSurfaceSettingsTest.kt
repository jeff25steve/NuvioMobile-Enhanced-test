package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidGlassSurfaceSettingsTest {
    @Test
    fun newCardsDefaultToGlassWithoutInheritingLibraryOverrides() {
        val legacySettings = decodeLiquidGlassSurfaceSettings("""{"library":{"enabled":false}}""")
        assertTrue(LiquidGlassSurface.PROFILE_WATCH_TIME.defaultEnabled)
        assertTrue(LiquidGlassSurface.SUPPORTER_MEMBERSHIP.defaultEnabled)
        assertNull(legacySettings.forSurface(LiquidGlassSurface.PROFILE_WATCH_TIME).enabled)
        assertNull(legacySettings.forSurface(LiquidGlassSurface.SUPPORTER_MEMBERSHIP).enabled)
    }

    @Test
    fun newSurfaceOverridesRoundTripIndependently() {
        val settings = LiquidGlassSurfacesSettings()
            .withSurface(LiquidGlassSurface.PROFILE_WATCH_TIME, LiquidGlassSurfaceOverrides(enabled = false, blurRadiusDp = 8f))
            .withSurface(LiquidGlassSurface.SUPPORTER_MEMBERSHIP, LiquidGlassSurfaceOverrides(enabled = true, tintOpacity = 0.24f))
        val decoded = decodeLiquidGlassSurfaceSettings(encodeLiquidGlassSurfaceSettings(settings))
        assertEquals(false, decoded.forSurface(LiquidGlassSurface.PROFILE_WATCH_TIME).enabled)
        assertEquals(8f, decoded.forSurface(LiquidGlassSurface.PROFILE_WATCH_TIME).blurRadiusDp)
        assertEquals(true, decoded.forSurface(LiquidGlassSurface.SUPPORTER_MEMBERSHIP).enabled)
        assertEquals(0.24f, decoded.forSurface(LiquidGlassSurface.SUPPORTER_MEMBERSHIP).tintOpacity)
    }
}
