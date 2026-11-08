package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchedStableDeleteIndexTest {

    @Test
    fun `fallback lookup preserves the first compatible entry in map order`() {
        val first = watchedItem(id = "show", type = "series", name = "First")
        val second = watchedItem(id = "show", type = "show", name = "Second")
        val items = linkedMapOf("legacy-first" to first, "legacy-second" to second)
        val index = WatchedStableDeleteIndex(items)

        assertEquals(
            "legacy-first",
            index.findKey(
                contentId = "show",
                contentType = "tv",
                season = 1,
                episode = 2,
            ),
        )
    }

    @Test
    fun `removing the first fallback match exposes the next compatible entry`() {
        val first = watchedItem(id = "show", type = "series", name = "First")
        val second = watchedItem(id = "show", type = "show", name = "Second")
        val items = linkedMapOf("legacy-first" to first, "legacy-second" to second)
        val index = WatchedStableDeleteIndex(items)

        assertEquals(first, index.remove("legacy-first"))
        assertEquals(
            "legacy-second",
            index.findKey(
                contentId = "show",
                contentType = "series",
                season = 1,
                episode = 2,
            ),
        )
    }

    @Test
    fun `upsert reindexes changed logical identity without changing original map order`() {
        val first = watchedItem(id = "show-a", type = "series", name = "First")
        val second = watchedItem(id = "show-b", type = "series", name = "Second")
        val third = watchedItem(id = "show-b", type = "show", name = "Third")
        val items = linkedMapOf("first" to first, "second" to second, "third" to third)
        val index = WatchedStableDeleteIndex(items)

        index.upsert("first", first.copy(id = "show-b", name = "Updated first"))

        assertEquals(
            "first",
            index.findKey(
                contentId = "show-b",
                contentType = "series",
                season = 1,
                episode = 2,
            ),
        )
        assertNull(
            index.findKey(
                contentId = "show-a",
                contentType = "series",
                season = 1,
                episode = 2,
            ),
        )
    }

    @Test
    fun `newly inserted and removed keys stay synchronized with fallback index`() {
        val existing = watchedItem(id = "show", type = "series", name = "Existing")
        val added = watchedItem(id = "show", type = "show", name = "Added")
        val items = linkedMapOf("existing" to existing)
        val index = WatchedStableDeleteIndex(items)

        index.upsert("added", added)
        assertEquals(
            "existing",
            index.findKey("show", "tv", season = 1, episode = 2),
        )

        index.remove("existing")
        assertEquals(
            "added",
            index.findKey("show", "tv", season = 1, episode = 2),
        )
    }

    @Test
    fun `fallback index retains type compatibility semantics`() {
        val movie = watchedItem(id = "show", type = "movie", name = "Movie")
        val series = watchedItem(id = "show", type = "series", name = "Series")
        val index = WatchedStableDeleteIndex(linkedMapOf("movie" to movie, "series" to series))

        assertEquals(
            "series",
            index.findKey("show", "show", season = 1, episode = 2),
        )
        assertNull(
            index.findKey("show", "film", season = 1, episode = 2),
        )
    }

    private fun watchedItem(id: String, type: String, name: String): WatchedItem =
        WatchedItem(
            id = id,
            type = type,
            name = name,
            season = 1,
            episode = 2,
            markedAtEpochMs = 1L,
        )
}
