package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HomeCatalogPosterShuffleTest {
    private val posters = (1..20).map { "movie:$it" }

    @Test
    fun disabledShuffleReturnsTheOriginalListAndOrder() {
        val input = posters.toMutableList()
        val result = orderCatalogPosters(
            items = input,
            catalogKey = "popular-movies",
            sessionSeed = 42L,
            enabled = false,
            stableKey = { it },
        )

        assertSame(input, result)
        assertEquals(input, result)
    }

    @Test
    fun enabledShuffleIsDeterministicForTheSameSessionAndCatalog() {
        val first = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }
        val second = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }

        assertEquals(first, second)
        assertFalse(first == posters)
    }

    @Test
    fun newSessionSeedProducesANewPosterOrder() {
        val first = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }
        val nextSession = orderCatalogPosters(posters, "popular-movies", 43L, true) { it }

        assertFalse(first == nextSession)
        assertEquals(posters.toSet(), nextSession.toSet())
    }

    @Test
    fun eachCatalogGetsItsOwnOrderForTheSameSession() {
        val movieOrder = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }
        val seriesOrder = orderCatalogPosters(posters, "popular-series", 42L, true) { it }

        assertFalse(movieOrder == seriesOrder)
        assertEquals(posters.toSet(), movieOrder.toSet())
        assertEquals(posters.toSet(), seriesOrder.toSet())
    }

    @Test
    fun orderingDoesNotDependOnTheInputListOrder() {
        val expected = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }
        val reversed = orderCatalogPosters(posters.reversed(), "popular-movies", 42L, true) { it }

        assertEquals(expected, reversed)
    }

    @Test
    fun addingPostersKeepsTheRelativeOrderOfAlreadyLoadedPosters() {
        val firstBatch = posters.take(8)
        val expandedBatch = firstBatch + posters.drop(8)

        val firstOrder = orderCatalogPosters(firstBatch, "popular-movies", 42L, true) { it }
        val expandedExistingOrder = orderCatalogPosters(
            expandedBatch,
            "popular-movies",
            42L,
            true,
        ) { it }.filter { it in firstBatch }

        assertEquals(firstOrder, expandedExistingOrder)
    }

    @Test
    fun shufflingPreservesEveryPosterExactlyOnce() {
        val shuffled = orderCatalogPosters(posters, "popular-movies", 42L, true) { it }

        assertEquals(posters.size, shuffled.size)
        assertEquals(posters.toSet(), shuffled.toSet())
        assertEquals(posters.size, shuffled.distinct().size)
    }

    @Test
    fun emptyAndSingleItemListsAreReturnedWithoutReordering() {
        val empty = emptyList<String>()
        val single = listOf("movie:only")

        assertSame(
            empty,
            orderCatalogPosters(empty, "popular-movies", 42L, true) { it },
        )
        assertSame(
            single,
            orderCatalogPosters(single, "popular-movies", 42L, true) { it },
        )
    }

    @Test
    fun shuffledOrderIsNotAnAlphabeticalSort() {
        val ordered = orderCatalogPosters(posters, "popular-movies", 99L, true) { it }

        assertTrue(ordered != posters.sorted())
    }
}
