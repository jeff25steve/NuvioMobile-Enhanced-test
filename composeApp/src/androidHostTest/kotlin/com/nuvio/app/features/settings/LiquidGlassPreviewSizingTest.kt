package com.nuvio.app.features.settings

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class LiquidGlassPreviewSizingTest {
    @Test
    fun playerPreviewHeightPreservesLandscapeSixteenByNineRatio() {
        assertEquals(180.dp, liquidGlassPlayerPreviewHeight(320.dp))
        assertEquals(202.5.dp, liquidGlassPlayerPreviewHeight(360.dp))
    }

    @Test
    fun playerPreviewScaleUsesAnEightHundredDpLandscapeCanvas() {
        assertEquals(0.4f, liquidGlassPlayerPreviewScale(320.dp), 0.001f)
        assertEquals(0.45f, liquidGlassPlayerPreviewScale(360.dp), 0.001f)
        assertEquals(1f, liquidGlassPlayerPreviewScale(800.dp), 0.001f)
    }
}
