package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidGlassSurfaceSettingsTest {
    @Test
    fun settingsCardsFollowTheExistingSettingsSurfaceToggle() {
        val defaults = LiquidGlassSurfacesSettings()
        val surface = LiquidGlassSurface.SETTINGS

        assertFalse(
            shouldRenderLiquidGlassSurface(
                surface = surface,
                overrides = defaults.forSurface(surface),
                preview = false,
                masterEnabled = true,
            ),
        )

        val enabled = defaults.withSurface(
            surface,
            LiquidGlassSurfaceOverrides(enabled = true),
        )
        assertTrue(
            shouldRenderLiquidGlassSurface(
                surface = surface,
                overrides = enabled.forSurface(surface),
                preview = false,
                masterEnabled = true,
            ),
        )
        assertFalse(
            shouldRenderLiquidGlassSurface(
                surface = surface,
                overrides = enabled.forSurface(surface),
                preview = false,
                masterEnabled = false,
            ),
        )
    }

    @Test
    fun settingsTogglePersistsWithoutCardSpecificSettings() {
        val encoded = encodeLiquidGlassSurfaceSettings(
            LiquidGlassSurfacesSettings().withSurface(
                LiquidGlassSurface.SETTINGS,
                LiquidGlassSurfaceOverrides(enabled = true),
            ),
        )
        val decoded = decodeLiquidGlassSurfaceSettings(encoded)

        assertTrue(decoded.forSurface(LiquidGlassSurface.SETTINGS).enabled == true)
        assertFalse(encoded.contains("profileWatchTime"))
        assertFalse(encoded.contains("supporterMembership"))
    }
}
