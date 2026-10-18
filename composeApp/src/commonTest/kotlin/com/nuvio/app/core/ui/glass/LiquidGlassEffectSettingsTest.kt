package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidGlassEffectSettingsTest {
    @Test
    fun sanitizesInvalidAndOutOfRangeValues() {
        val defaults = LiquidGlassEffectDefaults.floatingBar
        val dirty = defaults.copy(
            cornerRadiusPercent = Float.NaN,
            blurRadiusDp = -5f,
            refractionHeightDp = 1000f,
            refractionAmountDp = Float.POSITIVE_INFINITY,
            tintOpacity = Float.NEGATIVE_INFINITY,
        )

        val safe = dirty.sanitized(defaults, LiquidGlassEffectDefaults.floatingBarLimits)

        assertEquals(defaults.cornerRadiusPercent, safe.cornerRadiusPercent)
        assertEquals(0f, safe.blurRadiusDp)
        assertEquals(LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionHeightDp, safe.refractionHeightDp)
        assertEquals(defaults.refractionAmountDp, safe.refractionAmountDp)
        assertEquals(defaults.tintOpacity, safe.tintOpacity)
    }

    @Test
    fun settingsRoundTripThroughJson() {
        val defaults = LiquidGlassEffectDefaults.selectionLens
        val value = defaults.copy(
            cornerRadiusPercent = 23f,
            blurRadiusDp = 4f,
            refractionHeightDp = 14f,
            refractionAmountDp = 18f,
            chromaticAberration = false,
            depthEffect = false,
            tintOpacity = 0.35f,
            affectTabIcons = false,
            affectFloatingBar = true,
        )

        val encoded = encodeLiquidGlassEffectSettings(
            value,
            defaults,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )
        val decoded = decodeLiquidGlassEffectSettings(
            encoded,
            defaults,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )

        assertEquals(value, decoded)
    }

    @Test
    fun malformedSettingsFallBackToDefaults() {
        val defaults = LiquidGlassEffectDefaults.selectionLens
        assertEquals(
            defaults,
            decodeLiquidGlassEffectSettings(
                """{"blurRadiusDp":"not-a-number","depthEffect":false""",
                defaults,
                LiquidGlassEffectDefaults.selectionLensLimits,
            ),
        )
        assertTrue(defaults.affectTabIcons)
        assertTrue(defaults.affectFloatingBar)
        assertEquals(0f, defaults.sanitized(defaults, LiquidGlassEffectDefaults.selectionLensLimits).tintOpacity)
    }
}
