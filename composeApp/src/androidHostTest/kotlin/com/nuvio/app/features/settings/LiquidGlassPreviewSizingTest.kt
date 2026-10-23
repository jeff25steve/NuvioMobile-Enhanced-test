package com.nuvio.app.features.settings

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
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

    @Test
    fun highlightSwitchDefaultsMatchProductionSurfaceRecipes() {
        assertEquals(false, liquidGlassSurfaceDefaultHighlightEnabled(LiquidGlassSurface.DETAIL_HEADER))
        assertEquals(true, liquidGlassSurfaceDefaultHighlightEnabled(LiquidGlassSurface.DETAILS))
        assertEquals(true, liquidGlassSurfaceDefaultHighlightEnabled(LiquidGlassSurface.BOTTOM_SHEETS))
        assertEquals(true, liquidGlassSurfaceDefaultHighlightEnabled(LiquidGlassSurface.STREAM_RESULTS))
    }

    @Test
    fun detailActionPreviewTargetsMatchTheConfiguredProductionActionLayout() {
        val detailsOnly = setOf(LiquidGlassSurface.DETAILS)
        val detailsAndDownload = setOf(
            LiquidGlassSurface.DETAILS,
            LiquidGlassSurface.DETAIL_PRIMARY_ACTION,
        )

        assertEquals(
            detailsOnly,
            liquidGlassDetailActionPreviewSelectableSurfaces(
                iconActionRow = true,
                downloadActionAvailable = true,
            ),
        )
        assertEquals(
            detailsOnly,
            liquidGlassDetailActionPreviewSelectableSurfaces(
                iconActionRow = true,
                downloadActionAvailable = false,
            ),
        )
        assertEquals(
            detailsAndDownload,
            liquidGlassDetailActionPreviewSelectableSurfaces(
                iconActionRow = false,
                downloadActionAvailable = true,
            ),
        )
        assertEquals(
            detailsOnly,
            liquidGlassDetailActionPreviewSelectableSurfaces(
                iconActionRow = false,
                downloadActionAvailable = false,
            ),
        )
    }
}
