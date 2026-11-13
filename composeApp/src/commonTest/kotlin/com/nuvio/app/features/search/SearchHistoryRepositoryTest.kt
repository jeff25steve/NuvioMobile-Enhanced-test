package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryRepositoryTest {
    @Test
    fun `applySearchHistoryEntry moves existing query to front`() {
        val updated = applySearchHistoryEntry(
            current = listOf("batman", "dune", "silo"),
            query = "dune",
            limit = 10,
        )

        assertEquals(listOf("dune", "batman", "silo"), updated)
    }

    @Test
    fun `applySearchHistoryEntry deduplicates existing query ignoring case`() {
        val updated = applySearchHistoryEntry(
            current = listOf("Dune", "Arrival"),
            query = "dUnE",
            limit = 10,
        )

        assertEquals(listOf("dUnE", "Arrival"), updated)
    }

    @Test
    fun `normalizeSearchHistoryEntries removes case insensitive duplicates`() {
        val normalized = normalizeSearchHistoryEntries(
            entries = listOf(" Dune ", "dune", "Arrival", "ARRIVAL", " "),
            limit = 10,
        )

        assertEquals(listOf("Dune", "Arrival"), normalized)
    }

    @Test
    fun `normalizeSearchHistoryEntries applies limit after filtering`() {
        val normalized = normalizeSearchHistoryEntries(
            entries = listOf("A", "Dune", "dune", "Arrival", "Alien"),
            limit = 2,
        )

        assertEquals(listOf("Dune", "Arrival"), normalized)
    }

    @Test
    fun `applySearchHistoryEntry respects max size`() {
        val updated = applySearchHistoryEntry(
            current = listOf("a", "b", "c"),
            query = "d",
            limit = 3,
        )

        assertEquals(listOf("d", "a", "b"), updated)
    }
}
