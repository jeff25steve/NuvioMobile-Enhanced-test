package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.servers.ServerSearchRequestSignature
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class AddonSearchRequestSignature(
    val manifestUrl: String,
    val manifestId: String,
    val transportUrl: String,
    val displayTitle: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val supportsPagination: Boolean,
)

internal data class SearchRequestSignature(
    val normalizedQuery: String,
    val hideUnreleasedContent: Boolean,
    val hasPendingAddonManifests: Boolean,
    val addonRequests: List<AddonSearchRequestSignature>,
    val serverRequests: List<ServerSearchRequestSignature>,
)

internal fun buildSearchRequestSignature(
    query: String,
    hideUnreleasedContent: Boolean,
    hasPendingAddonManifests: Boolean,
    addonRequests: List<AddonSearchRequestSignature>,
    serverRequests: List<ServerSearchRequestSignature>,
): SearchRequestSignature = SearchRequestSignature(
    normalizedQuery = query.trim().lowercase(),
    hideUnreleasedContent = hideUnreleasedContent,
    hasPendingAddonManifests = hasPendingAddonManifests,
    addonRequests = addonRequests,
    serverRequests = serverRequests,
)

enum class SearchEmptyStateReason {
    NoActiveAddons,
    NoSearchCatalogs,
    NoResults,
    RequestFailed,
}

data class SearchUiState(
    val isLoading: Boolean = false,
    val sections: List<HomeCatalogSection> = emptyList(),
    val emptyStateReason: SearchEmptyStateReason? = null,
    val errorMessage: String? = null,
)

internal fun SearchUiState.forPendingQuery(): SearchUiState = copy(
    isLoading = true,
    sections = emptyList(),
    emptyStateReason = null,
    errorMessage = null,
)

enum class DiscoverEmptyStateReason {
    NoActiveAddons,
    NoDiscoverCatalogs,
    NoResults,
    RequestFailed,
}

data class DiscoverCatalogOption(
    val key: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val supportsPagination: Boolean = false,
    val server: CatalogTarget.Server? = null,
)

data class DiscoverUiState(
    val typeOptions: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogOptions: List<DiscoverCatalogOption> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = selectedCatalog?.genreOptions.orEmpty()

    val canLoadMore: Boolean
        get() = nextSkip != null
}


/**
 * Prevents cancelled or superseded search/discover jobs from publishing stale state.
 * Cancellation remains the primary resource-saving mechanism; this token is the
 * final state-publication guard for work that completes concurrently with cancellation.
 */
internal class SearchRequestGeneration {
    private val lock = SynchronizedObject()
    private var current = 0

    fun begin(): Int = synchronized(lock) {
        ++current
    }

    fun invalidate() {
        synchronized(lock) {
            current++
        }
    }

    fun isCurrent(generation: Int): Boolean = synchronized(lock) {
        current == generation
    }

    fun ifCurrent(generation: Int, action: () -> Unit) {
        synchronized(lock) {
            if (current == generation) action()
        }
    }
}
