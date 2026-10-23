package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color

class LiquidGlassSurfaceSettingsTest {
    @Test
    fun adaptiveGlassFilmAddsSheenOnBlackAndShadeOnWhite() {
        val blackFilm = liquidGlassAdaptiveFilm(Color.Black)
        val whiteFilm = liquidGlassAdaptiveFilm(Color.White)

        assertTrue(blackFilm.topSheenAlpha > whiteFilm.topSheenAlpha)
        assertTrue(whiteFilm.bottomShadeAlpha > blackFilm.bottomShadeAlpha)
        assertTrue(blackFilm.topSheenAlpha in 0f..0.14f)
        assertTrue(whiteFilm.bottomShadeAlpha in 0f..0.08f)
    }

    @Test
    fun existingGlassSurfacesRemainEnabledAndNewSurfacesKeepTheirCurrentAppearance() {
        assertTrue(LiquidGlassSurface.PLAYER.defaultEnabled)
        assertTrue(LiquidGlassSurface.STREAM_SELECTION.defaultEnabled)
        assertTrue(LiquidGlassSurface.DETAILS.defaultEnabled)
        assertTrue(LiquidGlassSurface.BOTTOM_SHEETS.defaultEnabled)

        assertFalse(LiquidGlassSurface.SEARCH.defaultEnabled)
        assertFalse(LiquidGlassSurface.LIBRARY.defaultEnabled)
        assertFalse(LiquidGlassSurface.SETTINGS.defaultEnabled)
    }

    @Test
    fun unsetOverridesInheritTheOriginalRecipeAndCanBeResetIndependently() {
        val initial = LiquidGlassSurfacesSettings()
        assertEquals(LiquidGlassSurfaceOverrides(), initial.forSurface(LiquidGlassSurface.PLAYER))
        assertEquals(LiquidGlassSurfaceOverrides(), initial.forSurface(LiquidGlassSurface.SEARCH))

        val customized = initial.withSurface(
            LiquidGlassSurface.SEARCH,
            LiquidGlassSurfaceOverrides(enabled = true, blurRadiusDp = 12f),
        )
        assertEquals(true, customized.forSurface(LiquidGlassSurface.SEARCH).enabled)
        assertEquals(12f, customized.forSurface(LiquidGlassSurface.SEARCH).blurRadiusDp)
        assertEquals(LiquidGlassSurfaceOverrides(), customized.forSurface(LiquidGlassSurface.PLAYER))

        assertEquals(initial, customized.withSurface(LiquidGlassSurface.SEARCH, LiquidGlassSurfaceOverrides()))
    }

    @Test
    fun sanitizingClampsNumericValuesWithoutInventingOverrides() {
        val sanitized = LiquidGlassSurfaceOverrides(
            cornerRadiusPercent = 80f,
            blurRadiusDp = -4f,
            refractionHeightDp = 200f,
            refractionAmountDp = Float.NaN,
            tintOpacity = 2f,
            defaultIconColorArgb = 0x006699CC,
        ).sanitized()

        assertEquals(50f, sanitized.cornerRadiusPercent)
        assertEquals(0f, sanitized.blurRadiusDp)
        assertEquals(48f, sanitized.refractionHeightDp)
        assertNull(sanitized.refractionAmountDp)
        assertEquals(1f, sanitized.tintOpacity)
        assertEquals(0xFF6699CC.toInt(), sanitized.defaultIconColorArgb)

        assertEquals(null, LiquidGlassSurfaceOverrides().sanitized().blurRadiusDp)
    }

    @Test
    fun surfacePreferencesRoundTripAndMalformedStorageFallsBackSafely() {
        val settings = LiquidGlassSurfacesSettings().withSurface(
            LiquidGlassSurface.PLAYER,
            LiquidGlassSurfaceOverrides(enabled = false, blurRadiusDp = 7.5f),
        ).withSurface(
            LiquidGlassSurface.LIBRARY,
            LiquidGlassSurfaceOverrides(enabled = true, defaultIconColorArgb = 0xFF336699.toInt()),
        ).withSurface(
            LiquidGlassSurface.BOTTOM_SHEETS,
            LiquidGlassSurfaceOverrides(
                enabled = false,
                cornerRadiusPercent = 22f,
                withVibrancy = false,
                withHighlight = false,
                withShadow = true,
            ),
        )

        val encoded = encodeLiquidGlassSurfaceSettings(settings)
        assertEquals(settings, decodeLiquidGlassSurfaceSettings(encoded))
        assertEquals(LiquidGlassSurfacesSettings(), decodeLiquidGlassSurfaceSettings("{broken-json"))
        assertEquals(LiquidGlassSurfacesSettings(), decodeLiquidGlassSurfaceSettings(null))
    }
}
