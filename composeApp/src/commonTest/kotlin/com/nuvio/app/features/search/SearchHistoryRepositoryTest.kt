package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryRepositoryTest {
    @Test
    fun `pending query state hides results from the previous query`() {
        val pending = SearchUiState(
            isLoading = false,
            sections = emptyList(),
            emptyStateReason = SearchEmptyStateReason.NoResults,
            errorMessage = "old error",
        ).forPendingQuery()

        assertEquals(true, pending.isLoading)
        assertEquals(emptyList(), pending.sections)
        assertEquals(null, pending.emptyStateReason)
        assertEquals(null, pending.errorMessage)
    }

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
            current = listOf("aa", "bb", "cc"),
            query = "dd",
            limit = 3,
        )

        assertEquals(listOf("d", "a", "b"), updated)
    }
    @Test
    fun `normalizeSearchHistoryEntries stops reading after reaching the limit`() {
        val entries = object : AbstractList<String>() {
            override val size: Int = 3

            override fun get(index: Int): String = when (index) {
                0 -> "Dune"
                1 -> "Arrival"
                else -> error("Entries after the limit must not be read")
            }
        }

        assertEquals(
            listOf("Dune", "Arrival"),
            normalizeSearchHistoryEntries(entries = entries, limit = 2),
        )
    }

    @Test
    fun `applySearchHistoryEntry keeps query first and only reads enough existing entries`() {
        val current = object : AbstractList<String>() {
            override val size: Int = 3

            override fun get(index: Int): String = when (index) {
                0 -> "Dune"
                1 -> "Arrival"
                else -> error("Entries after the limit must not be read")
            }
        }

        assertEquals(
            listOf("Silo", "Dune", "Arrival"),
            applySearchHistoryEntry(current = current, query = " Silo ", limit = 3),
        )
    }

    @Test
    fun `removeSearchHistoryEntry removes a match ignoring case`() {
        assertEquals(
            listOf("Arrival"),
            removeSearchHistoryEntry(
                current = listOf("Dune", "Arrival"),
                query = "dUnE",
            ),
        )
    }

    @Test
    fun `removeSearchHistoryEntry trims the requested query`() {
        assertEquals(
            listOf("Arrival"),
            removeSearchHistoryEntry(
                current = listOf("Dune", "Arrival"),
                query = "  Dune  ",
            ),
        )
    }

}
