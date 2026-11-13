package com.nuvio.app.features.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SearchHistoryRepository {
    private const val MAX_RECENT_SEARCHES = 10

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow<List<String>>(emptyList())
    val uiState: StateFlow<List<String>> = _uiState.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hasLoaded = false
    private var recentSearches: List<String> = emptyList()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        recentSearches = emptyList()
        _enabled.value = true
        publish()
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        SearchHistoryStorage.saveEnabled(enabled)
        publish()
    }

    fun recordSearch(query: String) {
        ensureLoaded()
        if (!_enabled.value) return
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return

        val updatedSearches = applySearchHistoryEntry(
            current = recentSearches,
            query = normalizedQuery,
            limit = MAX_RECENT_SEARCHES,
        )
        if (updatedSearches == recentSearches) return

        recentSearches = updatedSearches
        publish()
        persist()
    }

    fun removeSearch(query: String) {
        ensureLoaded()
        val updatedSearches = recentSearches.filterNot { it == query }
        if (updatedSearches == recentSearches) return

        recentSearches = updatedSearches
        publish()
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _enabled.value = SearchHistoryStorage.loadEnabled() ?: true
        val payload = SearchHistoryStorage.loadPayload().orEmpty().trim()
        recentSearches = if (payload.isEmpty()) {
            emptyList()
        } else {
            normalizeSearchHistoryEntries(
                entries = runCatching {
                    json.decodeFromString<List<String>>(payload)
                }.getOrDefault(emptyList()),
                limit = MAX_RECENT_SEARCHES,
            )
        }
        publish()
    }

    private fun publish() {
        _uiState.value = if (_enabled.value) recentSearches else emptyList()
    }

    private fun persist() {
        SearchHistoryStorage.savePayload(json.encodeToString(recentSearches))
    }
}

internal fun normalizeSearchHistoryEntries(
    entries: List<String>,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()

    return buildList {
        entries.forEach { entry ->
            val normalized = entry.trim()
            if (
                normalized.length >= 2 &&
                size < limit &&
                none { existing -> existing.equals(normalized, ignoreCase = true) }
            ) {
                add(normalized)
            }
        }
    }
}

internal fun applySearchHistoryEntry(
    current: List<String>,
    query: String,
    limit: Int,
): List<String> = normalizeSearchHistoryEntries(
    entries = listOf(query) + current,
    limit = limit,
)
