package com.nuvio.app.features.catalog

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogHeaderBackdropPolicyTest {
    @Test
    fun posterBackdropIsAvailableToBothCategoryHeaderGlassSurfacesOnly() {
        assertTrue(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON))
        assertTrue(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.CATEGORY_HEADER_TITLE))
        assertFalse(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.DETAILS))
        assertFalse(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.SETTINGS))
    }

    @Test
    fun studioPreviewGlassIsScopedToTheHighlightedCategoryHeaderSurface() {
        assertTrue(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_TITLE,
                previewMode = true,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_TITLE,
            ),
        )
        assertFalse(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
                previewMode = true,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_TITLE,
            ),
        )
        assertTrue(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
                previewMode = true,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
            ),
        )
        assertFalse(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_TITLE,
                previewMode = true,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
            ),
        )
    }

    @Test
    fun liveCategoryHeaderGlassIsIndependentOfEditorSelection() {
        assertTrue(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_TITLE,
                previewMode = false,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
            ),
        )
        assertTrue(
            categoryHeaderPreviewUsesGlass(
                LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON,
                previewMode = false,
                selectedPreviewSurface = LiquidGlassSurface.CATEGORY_HEADER_TITLE,
            ),
        )
    }
}
