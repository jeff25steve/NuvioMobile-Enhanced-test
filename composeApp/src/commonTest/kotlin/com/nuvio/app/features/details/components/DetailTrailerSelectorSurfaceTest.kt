package com.nuvio.app.features.details.components

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailTrailerSelectorSurfaceTest {
    @Test
    fun nativeSelectorIsAvailableForLiveSurfaceWhenGlassIsEnabled() {
        assertTrue(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = true,
                surfaceEnabled = true,
                preview = false,
                selectedPreviewSurface = null,
                previewMenuInitiallyExpanded = false,
            ),
        )
    }

    @Test
    fun previewCanSelectOnlyTheTrailerSelectorSurface() {
        assertTrue(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = true,
                surfaceEnabled = true,
                preview = true,
                selectedPreviewSurface = LiquidGlassSurface.DETAIL_TRAILER_SELECTOR,
                previewMenuInitiallyExpanded = false,
            ),
        )
        assertFalse(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = true,
                surfaceEnabled = true,
                preview = true,
                selectedPreviewSurface = LiquidGlassSurface.DETAIL_TRAILER_MENU,
                previewMenuInitiallyExpanded = false,
            ),
        )
    }

    @Test
    fun nativeSelectorFallsBackWhenDisabledOrMenuPreviewIsOpen() {
        assertFalse(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = true,
                surfaceEnabled = false,
                preview = false,
                selectedPreviewSurface = null,
                previewMenuInitiallyExpanded = false,
            ),
        )
        assertFalse(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = true,
                surfaceEnabled = true,
                preview = true,
                selectedPreviewSurface = LiquidGlassSurface.DETAIL_TRAILER_MENU,
                previewMenuInitiallyExpanded = true,
            ),
        )
        assertFalse(
            shouldUseNativeDetailTrailerSelector(
                nativeControlsAvailable = false,
                surfaceEnabled = true,
                preview = false,
                selectedPreviewSurface = null,
                previewMenuInitiallyExpanded = false,
            ),
        )
    }
}
