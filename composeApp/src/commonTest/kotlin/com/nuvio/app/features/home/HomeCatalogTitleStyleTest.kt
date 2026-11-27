package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeCatalogTitleStyleTest {
    @Test
    fun invalidValuesNormalizeToSafeDefaultsAndSupportedBounds() {
        val normalized = HomeCatalogTitleStyle(
            fontId = "untrusted-font-id",
            fontSizeSp = Int.MAX_VALUE,
            bold = true,
            colorId = "not-a-palette-color",
        ).normalized()

        assertEquals("default", normalized.fontId)
        assertEquals(HOME_CATALOG_TITLE_MAX_SIZE_SP, normalized.fontSizeSp)
        assertTrue(normalized.bold)
        assertNull(normalized.colorId)
    }

    @Test
    fun fontSizeIsClampedAtBothEnds() {
        assertEquals(
            HOME_CATALOG_TITLE_MIN_SIZE_SP,
            HomeCatalogTitleStyle(fontSizeSp = Int.MIN_VALUE).normalized().fontSizeSp,
        )
        assertEquals(
            HOME_CATALOG_TITLE_MAX_SIZE_SP,
            HomeCatalogTitleStyle(fontSizeSp = Int.MAX_VALUE).normalized().fontSizeSp,
        )
    }

    @Test
    fun knownCustomFontIdsAndPaletteColorsArePreserved() {
        val style = HomeCatalogTitleStyle(
            fontId = "custom-12345678-1234-1234-1234-123456789abc",
            fontSizeSp = 26,
            bold = true,
            colorId = "blue",
        ).normalized()

        assertEquals("custom-12345678-1234-1234-1234-123456789abc", style.fontId)
        assertEquals(26, style.fontSizeSp)
        assertEquals("blue", style.colorId)
        assertFalse(style.isDefault)
    }

    @Test
    fun defaultStyleAndThemeColorResolveWithoutCustomColor() {
        assertTrue(HomeCatalogTitleStyle.DEFAULT.isDefault)
        assertNull(homeCatalogTitleColorArgb(null, darkBackground = true))
        assertNotNull(homeCatalogTitleColorArgb("blue", darkBackground = true))
        assertNotNull(homeCatalogTitleColorArgb("blue", darkBackground = false))
        assertFalse(
            homeCatalogTitleColorArgb("blue", darkBackground = true) ==
                homeCatalogTitleColorArgb("blue", darkBackground = false),
        )
    }
}
