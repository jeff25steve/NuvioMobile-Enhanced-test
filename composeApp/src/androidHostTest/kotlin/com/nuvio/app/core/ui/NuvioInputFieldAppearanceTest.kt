package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuvioInputFieldAppearanceTest {
    @Test
    fun glassUsesTheOuterRimWithoutADuplicateMaterialOutline() {
        assertFalse(
            shouldDrawNuvioInputFieldOutline(
                glassSurfaceEnabled = true,
                glassRendererAvailable = true,
            ),
        )
    }

    @Test
    fun nativeOutlineRemainsWhenGlassIsDisabledOrFallsBack() {
        assertTrue(
            shouldDrawNuvioInputFieldOutline(
                glassSurfaceEnabled = false,
                glassRendererAvailable = true,
            ),
        )
        assertTrue(
            shouldDrawNuvioInputFieldOutline(
                glassSurfaceEnabled = true,
                glassRendererAvailable = false,
            ),
        )
    }
}
