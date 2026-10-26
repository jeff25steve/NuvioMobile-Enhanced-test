package com.nuvio.app.features.settings

import androidx.compose.ui.graphics.luminance
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
