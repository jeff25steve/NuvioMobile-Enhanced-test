package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiquidGlassBackgroundImageTargetTest {
    @Test
    fun settingsSurfaceUsesItsOwnBackgroundImageTarget() {
        assertEquals(
            LiquidGlassBackgroundImageTarget.SETTINGS,
            liquidGlassBackgroundImageTarget(LiquidGlassSurface.SETTINGS),
        )
    }

    @Test
    fun cloudSurfacesShareTheCloudBackgroundImageTarget() {
        listOf(
            LiquidGlassSurface.LIBRARY_CLOUD_FILTERS,
            LiquidGlassSurface.LIBRARY_CLOUD_SEARCH,
            LiquidGlassSurface.LIBRARY_CLOUD_CARDS,
        ).forEach { surface ->
            assertEquals(
                LiquidGlassBackgroundImageTarget.LIBRARY_CLOUD,
                liquidGlassBackgroundImageTarget(surface),
            )
        }
    }

    @Test
    fun unrelatedSurfacesDoNotExposeBackgroundImageControls() {
        assertNull(liquidGlassBackgroundImageTarget(LiquidGlassSurface.LIBRARY))
        assertNull(liquidGlassBackgroundImageTarget(LiquidGlassSurface.SEARCH))
    }
}
