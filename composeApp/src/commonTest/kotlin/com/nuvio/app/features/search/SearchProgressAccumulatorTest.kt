package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchProgressAccumulatorTest {
    @Test
    fun `failed addon results do not produce progressive UI snapshots`() {
        val progress = SearchProgressAccumulator(resultCount = 3)

        assertNull(progress.record(IndexedSearchResult(index = 2, error = IllegalStateException("offline"))))
        assertEquals(1, progress.completedResults().size)
        assertEquals(emptyList(), progress.orderedSections())
    }

    @Test
    fun `successful sections publish in configured source order`() {
        val progress = SearchProgressAccumulator(resultCount = 3)
        val first = section("first")
        val second = section("second")

        assertEquals(listOf(second), progress.record(IndexedSearchResult(index = 1, section = second)))
        assertEquals(listOf(first, second), progress.record(IndexedSearchResult(index = 0, section = first)))
        assertNull(progress.record(IndexedSearchResult(index = 2, error = IllegalStateException("offline"))))
        assertEquals(listOf(first, second), progress.orderedSections())
    }

    private fun section(key: String) = HomeCatalogSection(
        key = key,
        title = key,
        subtitle = "addon",
        addonName = "addon",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.com/manifest.json",
            contentType = "movie",
            catalogId = key,
            supportsPagination = false,
        ),
        items = emptyList(),
    )
}
