package com.nuvio.app.features.player

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.features.settings.surfacePreviewRecipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerLiquidGlassResponsivenessTest {
    @Test
    fun blurSliderChangesFallbackWhenNoVideoBackdropCanBeCaptured() {
        val baseAlpha = PlayerGlassFallback.alpha
        val noBlur = playerGlassFallbackAlpha(baseAlpha, blurRadiusDp = 0f)
        val maximumBlur = playerGlassFallbackAlpha(baseAlpha, blurRadiusDp = 32f)

        assertEquals(baseAlpha, noBlur)
        assertTrue(maximumBlur > noBlur)
        assertTrue(maximumBlur <= 0.22f)
    }

    @Test
    fun highlightToggleControlsPlayerRimWhenGlassIsEnabled() {
        assertEquals(
            0f,
            playerGlassRimAlpha(
                rimAlpha = PlayerGlassRim.alpha,
                surfaceEnabled = true,
                highlightEnabled = false,
            ),
        )
        assertEquals(
            PlayerGlassRim.alpha,
            playerGlassRimAlpha(
                rimAlpha = PlayerGlassRim.alpha,
                surfaceEnabled = false,
                highlightEnabled = false,
            ),
        )
    }

    @Test
    fun previewUsesTheSameDefaultBlurAsTheLivePlayer() {
        assertEquals(
            PlayerGlassBlur.value,
            surfacePreviewRecipe(LiquidGlassSurface.PLAYER, LiquidGlassSurfaceOverrides()).blurRadiusDp,
        )
    }
}
