package com.nuvio.app.features.catalog

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogHeaderBackdropPolicyTest {
    @Test
    fun posterBackdropIsReservedForTheCategoryBackButton() {
        assertTrue(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.CATEGORY_HEADER_BACK_BUTTON))
        assertFalse(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.CATEGORY_HEADER_TITLE))
        assertFalse(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.DETAILS))
        assertFalse(categoryHeaderUsesPosterBackdrop(LiquidGlassSurface.SETTINGS))
    }
}
