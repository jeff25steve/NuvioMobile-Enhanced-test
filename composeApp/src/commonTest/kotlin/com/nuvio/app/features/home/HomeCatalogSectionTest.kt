package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HomeCatalogSectionTest {
    @Test
    fun `can open catalog when page has more below preview limit`() {
        val section = section(
            availableItemCount = 11,
            hasMore = true,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `can open catalog when non paginated section exceeds preview limit`() {
        val section = section(
            availableItemCount = 19,
            hasMore = false,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `cannot open catalog when section fits preview and has no more pages`() {
        val section = section(
            availableItemCount = 11,
            hasMore = false,
        )

        assertFalse(section.canOpenCatalog(previewLimit = 18))
    }

    private fun section(
        availableItemCount: Int,
        hasMore: Boolean,
    ): HomeCatalogSection =
        HomeCatalogSection(
            key = "addon:movie:popular",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "popular",
                supportsPagination = hasMore,
            ),
            items = listOf(
                MetaPreview(
                    id = "tt1",
                    type = "movie",
                    name = "Movie",
                ),
            ),
            availableItemCount = availableItemCount,
            hasMore = hasMore,
        )

    @Test
    fun `poster overlay cache reuses transformed sections until source or pattern changes`() {
        val cache = HomePosterOverlayCache()
        val source = section(availableItemCount = 1, hasMore = false)
        var transformCount = 0

        fun transform(section: HomeCatalogSection): HomeCatalogSection {
            transformCount++
            return section.copy(title = "Transformed")
        }

        val first = cache.getOrApply("home:popular", source, "pattern-a", ::transform)
        val repeated = cache.getOrApply("home:popular", source, "pattern-a", ::transform)

        assertSame(first, repeated)
        assertEquals(1, transformCount)

        val changedPattern = cache.getOrApply("home:popular", source, "pattern-b", ::transform)
        assertNotSame(first, changedPattern)
        assertEquals(2, transformCount)

        val changedSource = cache.getOrApply("home:popular", source.copy(), "pattern-b", ::transform)
        assertNotSame(changedPattern, changedSource)
        assertEquals(3, transformCount)

        cache.retainKeys(emptySet())
        cache.getOrApply("home:popular", source.copy(), "pattern-b", ::transform)
        assertEquals(4, transformCount)
    }

}
