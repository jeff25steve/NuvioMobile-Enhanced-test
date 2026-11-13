package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRequestGenerationTest {

    @Test
    fun newerSearchSupersedesAnOlderSearch() {
        val generation = SearchRequestGeneration()

        val first = generation.begin()
        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun clearingSearchInvalidatesAnInFlightRequest() {
        val generation = SearchRequestGeneration()
        val inFlight = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(inFlight))
    }

    @Test
    fun newerDiscoverPageSupersedesAnOlderPageForSameSelection() {
        val generation = SearchRequestGeneration()

        val firstPage = generation.begin()
        val refreshedFirstPage = generation.begin()

        assertFalse(generation.isCurrent(firstPage))
        assertTrue(generation.isCurrent(refreshedFirstPage))
    }
}
