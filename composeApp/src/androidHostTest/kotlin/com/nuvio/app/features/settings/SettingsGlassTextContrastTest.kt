package com.nuvio.app.features.settings

import androidx.compose.ui.graphics.luminance
import com.nuvio.app.core.ui.glass.withLiquidGlassTextShadow
import com.nuvio.app.core.ui.glass.withLiquidGlassTextContrast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.defaultNuvioThemeTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsGlassTextContrastTest {
    @Test
    fun enabledGlassTextContrastBrightensTextTokensWithoutChangingSurfacesOrAccents() {
        val original = defaultNuvioThemeTokens(
            palette = ThemeColors.White,
            amoled = false,
            colorScheme = null,
        )

        val readable = original.withSettingsGlassTextContrast(enabled = true)

        assertTrue(readable.colors.textPrimary.luminance() > original.colors.textPrimary.luminance())
        assertTrue(readable.colors.textSecondary.luminance() > original.colors.textSecondary.luminance())
        assertTrue(readable.colors.textMuted.luminance() > original.colors.textMuted.luminance())
        assertEquals(original.colors.surfaceCard, readable.colors.surfaceCard)
        assertEquals(original.colors.accent, readable.colors.accent)
    }

    @Test
    fun liquidGlassContrastBrightensNeutralTextWithoutChangingAccentsOrSurfaces() {
        val original = defaultNuvioThemeTokens(
            palette = ThemeColors.White,
            amoled = false,
            colorScheme = null,
        )

        val readable = original.withLiquidGlassTextContrast(true)

        assertTrue(readable.colors.textPrimary.luminance() >= original.colors.textPrimary.luminance())
        assertTrue(readable.colors.textSecondary.luminance() > original.colors.textSecondary.luminance())
        assertTrue(readable.colors.textMuted.luminance() > original.colors.textMuted.luminance())
        assertEquals(original.colors.surfaceCard, readable.colors.surfaceCard)
        assertEquals(original.colors.surface, readable.colors.surface)
        assertEquals(original.colors.accent, readable.colors.accent)
        assertEquals(original.colors.neutral, readable.colors.neutral)
        assertTrue(readable.colors.textDisabled.luminance() > original.colors.textDisabled.luminance())
    }

    @Test
    fun liquidGlassColorSchemeBrightensSecondaryTextButPreservesSurfaceAndAccent() {
        val original = darkColorScheme(
            onSurface = Color(0xFFF5F7F8),
            onSurfaceVariant = Color(0xFF969CA3),
            onBackground = Color(0xFFF5F7F8),
            primary = Color(0xFF3185F5),
            surface = Color(0xFF141A24),
        )

        val readable = original.withLiquidGlassTextContrast(true)

        assertTrue(readable.onSurface.luminance() >= original.onSurface.luminance())
        assertTrue(readable.onSurfaceVariant.luminance() > original.onSurfaceVariant.luminance())
        assertTrue(readable.onBackground.luminance() >= original.onBackground.luminance())
        assertEquals(original.primary, readable.primary)
        assertEquals(original.surface, readable.surface)
        assertEquals(original, original.withLiquidGlassTextContrast(false))
    }

    @Test
    fun liquidGlassTextShadowUsesASubtleDarkEdgeAndCanBeDisabled() {
        val original = TextStyle()
        val readable = original.withLiquidGlassTextShadow(true)

        assertNotNull(readable.shadow)
        assertEquals(Color.Black.copy(alpha = 0.68f), readable.shadow?.color)
        assertEquals(Offset(0f, 1f), readable.shadow?.offset)
        assertEquals(3f, readable.shadow?.blurRadius)
        assertEquals(original, original.withLiquidGlassTextShadow(false))
    }

    @Test
    fun disabledGlassTextContrastLeavesThemeTokensUnchanged() {
        val original = defaultNuvioThemeTokens(
            palette = ThemeColors.White,
            amoled = false,
            colorScheme = null,
        )

        assertEquals(original, original.withSettingsGlassTextContrast(enabled = false))
    }

    @Test
    fun glassTextShadowIsAddedOnlyWhenGlassContrastIsEnabled() {
        val original = TextStyle()

        val readable = original.withSettingsGlassTextShadow(enabled = true)

        assertNotNull(readable.shadow)
        assertEquals(original, original.withSettingsGlassTextShadow(enabled = false))
    }
}
