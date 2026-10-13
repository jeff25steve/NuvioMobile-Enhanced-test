package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LiquidGlassEffectSettingsTest {
    @Test
    fun floatingBarAndSelectionLensHaveIndependentUpstreamBasedDefaults() {
        val bar = LiquidGlassEffectDefaults.floatingBar
        val lens = LiquidGlassEffectDefaults.selectionLens

        assertEquals(50f, bar.cornerRadiusPercent)
        assertEquals(8f, bar.blurRadiusDp)
        assertEquals(24f, bar.refractionHeightDp)
        assertEquals(24f, bar.refractionAmountDp)
        assertEquals(0.4f, bar.tintOpacity)
        assertEquals(false, bar.chromaticAberration)

        assertEquals(50f, lens.cornerRadiusPercent)
        assertEquals(0f, lens.blurRadiusDp)
        assertEquals(10f, lens.refractionHeightDp)
        assertEquals(14f, lens.refractionAmountDp)
        assertEquals(0.1f, lens.tintOpacity)
        assertEquals(true, lens.chromaticAberration)
        assertNotEquals(bar, lens)
    }

    @Test
    fun sanitizingClampsOutOfRangeValuesAndRestoresNonFiniteValues() {
        val defaults = LiquidGlassEffectDefaults.floatingBar
        val value = defaults.copy(
            cornerRadiusPercent = Float.POSITIVE_INFINITY,
            blurRadiusDp = -8f,
            refractionHeightDp = 100f,
            refractionAmountDp = Float.NaN,
            tintOpacity = 2f,
        )

        assertEquals(
            defaults.copy(
                blurRadiusDp = 0f,
                refractionHeightDp = 48f,
                tintOpacity = 1f,
            ),
            value.sanitized(defaults, LiquidGlassEffectDefaults.floatingBarLimits),
        )
    }

    @Test
    fun decodeUsesGroupSpecificDefaultsForMissingFields() {
        val defaults = LiquidGlassEffectDefaults.selectionLens
        val decoded = decodeLiquidGlassEffectSettings(
            """{"blurRadiusDp":12.0}""",
            defaults,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )

        assertEquals(defaults.copy(blurRadiusDp = 12f), decoded)
    }

    @Test
    fun malformedPayloadFallsBackToTheGroupDefaults() {
        val defaults = LiquidGlassEffectDefaults.selectionLens

        assertEquals(
            defaults,
            decodeLiquidGlassEffectSettings("{broken-json", defaults, LiquidGlassEffectDefaults.selectionLensLimits),
        )
        assertEquals(
            defaults,
            decodeLiquidGlassEffectSettings(null, defaults, LiquidGlassEffectDefaults.selectionLensLimits),
        )
    }

    @Test
    fun serializedSettingsRoundTripAfterSanitizing() {
        val defaults = LiquidGlassEffectDefaults.selectionLens
        val custom = defaults.copy(
            cornerRadiusPercent = 23f,
            blurRadiusDp = 6.5f,
            refractionHeightDp = 18f,
            refractionAmountDp = 22f,
            chromaticAberration = false,
            depthEffect = true,
            tintOpacity = 0.32f,
        )
        val encoded = encodeLiquidGlassEffectSettings(
            custom,
            defaults,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )

        assertEquals(
            custom,
            decodeLiquidGlassEffectSettings(encoded, defaults, LiquidGlassEffectDefaults.selectionLensLimits),
        )
    }
}
