package com.nuvio.app.features.search

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServersUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchRequestStateTest {
    @Test
    fun `matching request reuses completed state`() {
        assertTrue(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "query:addons:settings",
                cachedRequestKey = "query:addons:settings",
            ),
        )
    }

    @Test
    fun `changed or forced request reloads state`() {
        assertFalse(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "new-query",
                cachedRequestKey = "old-query",
            ),
        )
        assertFalse(
            canReuseRequestState(
                forceRefresh = true,
                requestKey = "same-query",
                cachedRequestKey = "same-query",
            ),
        )
    }

    @Test
    fun `addon search signature includes every request and displayed descriptor`() {
        val manifest = AddonManifest(
            id = "cinemeta",
            name = "CineMeta",
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource(name = "meta", types = listOf("movie"))),
            types = listOf("movie"),
            transportUrl = "https://addon.example/manifest.json",
        )
        val addon = ManagedAddon(
            manifestUrl = "https://addon.example/configured.json",
            manifest = manifest,
        )
        val request = SearchCatalogRequest(
            addon = addon,
            catalogId = "popular",
            catalogName = "Popular",
            type = "movie",
            query = "matrix",
            supportsPagination = false,
        )
        val baseline = request.toRequestSignature()
        val changedRequests = listOf(
            request.copy(addon = addon.copy(manifestUrl = "https://new.example/manifest.json")),
            request.copy(addon = addon.copy(manifest = manifest.copy(id = "other-addon"))),
            request.copy(addon = addon.copy(manifest = manifest.copy(transportUrl = "https://new.example/transport"))),
            request.copy(addon = addon.copy(manifest = manifest.copy(name = "Updated CineMeta"))),
            request.copy(addon = addon.copy(userSetName = "My Addon")),
            request.copy(catalogId = "trending"),
            request.copy(catalogName = "Trending"),
            request.copy(type = "series"),
            request.copy(supportsPagination = true),
        )

        assertTrue(changedRequests.all { it.toRequestSignature() != baseline })
    }

    @Test
    fun `search request signature preserves query normalization and filter inputs`() {
        val first = buildSearchRequestSignature(
            query = "  MATRIX ",
            hideUnreleasedContent = false,
            hasPendingAddonManifests = false,
            addonRequests = emptyList(),
            serverRequests = emptyList(),
        )
        val normalized = buildSearchRequestSignature(
            query = "matrix",
            hideUnreleasedContent = false,
            hasPendingAddonManifests = false,
            addonRequests = emptyList(),
            serverRequests = emptyList(),
        )

        assertEquals(first, normalized)
        assertNotEquals(
            first,
            buildSearchRequestSignature(
                query = "matrix",
                hideUnreleasedContent = true,
                hasPendingAddonManifests = false,
                addonRequests = emptyList(),
                serverRequests = emptyList(),
            ),
        )
        assertNotEquals(
            first,
            buildSearchRequestSignature(
                query = "matrix",
                hideUnreleasedContent = false,
                hasPendingAddonManifests = true,
                addonRequests = emptyList(),
                serverRequests = emptyList(),
            ),
        )
    }

    @Test
    fun `server configuration revision flow ignores failure-only updates`() = runTest {
        val serverState = MutableStateFlow(ServersUiState())
        val revisions = mutableListOf<Int>()

        backgroundScope.launch(Dispatchers.Unconfined) {
            serverConfigurationRevisionFlow(serverState).collect(revisions::add)
        }

        assertEquals(listOf(0), revisions)
        serverState.value = serverState.value.copy(
            failures = mapOf("server" to ServerFailure.UNREACHABLE),
        )
        assertEquals(listOf(0), revisions)

        serverState.value = serverState.value.copy(revision = 1)
        assertEquals(listOf(0, 1), revisions)

        serverState.value = serverState.value.copy(failures = emptyMap())
        assertEquals(listOf(0, 1), revisions)

        serverState.value = serverState.value.copy(revision = 2)
        assertEquals(listOf(0, 1, 2), revisions)
    }

    @Test
    fun `discover request key includes server configuration revision`() {
        val source = discoverCatalog(
            key = "server:connection:library",
            type = "movie",
        ).copy(server = CatalogTarget.Server("connection", "library", "movie"))
        val baseline = DiscoverRequestKey(
            sources = listOf(source),
            hideUnreleasedContent = false,
            hasPendingAddonManifests = false,
            serverConfigurationRevision = 1,
        )

        assertNotEquals(baseline, baseline.copy(serverConfigurationRevision = 2))
        assertEquals(baseline, baseline.copy(serverConfigurationRevision = 1))
    }

    @Test
    fun `preferred discover catalog is restored ahead of current fallback`() {
        val fallback = discoverCatalog(key = "fallback", type = "movie")
        val preferred = discoverCatalog(key = "preferred", type = "series")

        val selected = resolveDiscoverCatalog(
            sources = listOf(fallback, preferred),
            preferredCatalogKey = preferred.key,
            currentCatalogKey = fallback.key,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `current discover catalog remains when preference is unavailable`() {
        val current = discoverCatalog(key = "current", type = "movie")

        val selected = resolveDiscoverCatalog(
            sources = listOf(discoverCatalog(key = "first", type = "movie"), current),
            preferredCatalogKey = "unavailable",
            currentCatalogKey = current.key,
        )

        assertEquals(current, selected)
    }

    @Test
    fun `unresolved addon stays loading before surfacing manifest failure`() {
        val pendingAddon = ManagedAddon(
            manifestUrl = "https://pending.example/manifest.json",
            isRefreshing = true,
        )
        val failedAddon = pendingAddon.copy(
            isRefreshing = false,
            errorMessage = "Timed out",
        )

        try {
            SearchRepository.reset()
            SearchRepository.search(query = "movie", addons = listOf(pendingAddon))
            SearchRepository.refreshDiscover(addons = listOf(pendingAddon))

            assertTrue(SearchRepository.uiState.value.isLoading)
            assertEquals(null, SearchRepository.uiState.value.emptyStateReason)
            assertTrue(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(null, SearchRepository.discoverUiState.value.emptyStateReason)

            SearchRepository.search(query = "movie", addons = listOf(failedAddon))
            SearchRepository.refreshDiscover(addons = listOf(failedAddon))

            assertFalse(SearchRepository.uiState.value.isLoading)
            assertEquals(SearchEmptyStateReason.RequestFailed, SearchRepository.uiState.value.emptyStateReason)
            assertEquals("Timed out", SearchRepository.uiState.value.errorMessage)
            assertFalse(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(
                DiscoverEmptyStateReason.RequestFailed,
                SearchRepository.discoverUiState.value.emptyStateReason,
            )
            assertEquals("Timed out", SearchRepository.discoverUiState.value.errorMessage)
        } finally {
            SearchRepository.reset()
        }
    }

    @Test
    fun `pending addon settles to catalog capability empty states`() {
        val loadedAddon = ManagedAddon(
            manifestUrl = "https://loaded.example/manifest.json",
            manifest = AddonManifest(
                id = "loaded",
                name = "Loaded",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "meta", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = emptyList(),
                transportUrl = "https://loaded.example/manifest.json",
            ),
        )
        val pendingAddon = ManagedAddon(
            manifestUrl = "https://pending.example/manifest.json",
            isRefreshing = true,
        )
        val failedAddon = pendingAddon.copy(
            isRefreshing = false,
            errorMessage = "Timed out",
        )

        try {
            SearchRepository.reset()
            SearchRepository.search(query = "movie", addons = listOf(loadedAddon, pendingAddon))
            SearchRepository.refreshDiscover(addons = listOf(loadedAddon, pendingAddon))

            assertTrue(SearchRepository.uiState.value.isLoading)
            assertTrue(SearchRepository.discoverUiState.value.isLoading)

            SearchRepository.search(query = "movie", addons = listOf(loadedAddon, failedAddon))
            SearchRepository.refreshDiscover(addons = listOf(loadedAddon, failedAddon))

            assertFalse(SearchRepository.uiState.value.isLoading)
            assertEquals(SearchEmptyStateReason.NoSearchCatalogs, SearchRepository.uiState.value.emptyStateReason)
            assertFalse(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(
                DiscoverEmptyStateReason.NoDiscoverCatalogs,
                SearchRepository.discoverUiState.value.emptyStateReason,
            )
        } finally {
            SearchRepository.reset()
        }
    }


    @Test
    fun `search catalog builder preserves addon and catalog order and filters unsupported catalogs`() {
        val firstAddon = managedAddon(
            id = "first",
            name = "First addon",
            catalogs = listOf(
                AddonCatalog(
                    type = "movie",
                    id = "popular",
                    name = "Popular",
                    extra = listOf(AddonExtraProperty(name = "search")),
                ),
                AddonCatalog(type = "movie", id = "all", name = "All"),
                AddonCatalog(
                    type = "movie",
                    id = "restricted",
                    name = "Restricted",
                    extra = listOf(
                        AddonExtraProperty(name = "search"),
                        AddonExtraProperty(name = "genre", isRequired = true),
                    ),
                ),
            ),
        )
        val secondAddon = managedAddon(
            id = "second",
            name = "Second addon",
            catalogs = listOf(
                AddonCatalog(
                    type = "series",
                    id = "trending",
                    name = "Trending",
                    extra = listOf(
                        AddonExtraProperty(name = "search"),
                        AddonExtraProperty(name = "skip"),
                    ),
                ),
            ),
        )

        val requests = SearchRepository.buildSearchRequests(
            addons = listOf(
                firstAddon,
                ManagedAddon(manifestUrl = "https://pending.example/manifest.json"),
                secondAddon,
            ),
            query = "matrix",
        )

        assertEquals(listOf("popular", "trending"), requests.map { it.catalogId })
        assertEquals(listOf("First addon", "Second addon"), requests.map { it.addon.displayTitle })
        assertEquals(listOf("matrix", "matrix"), requests.map { it.query })
        assertEquals(listOf(false, true), requests.map { it.supportsPagination })
    }

    @Test
    fun `discover source builder preserves supported catalogs and genre metadata in source order`() {
        val addon = managedAddon(
            id = "discover",
            name = "Discover addon",
            catalogs = listOf(
                AddonCatalog(type = "movie", id = "featured", name = "Featured"),
                AddonCatalog(
                    type = "series",
                    id = "genre",
                    name = "By genre",
                    extra = listOf(
                        AddonExtraProperty(
                            name = "genre",
                            isRequired = true,
                            options = listOf("Action", "Drama"),
                        ),
                    ),
                ),
                AddonCatalog(
                    type = "movie",
                    id = "empty-genre",
                    name = "Empty genre",
                    extra = listOf(AddonExtraProperty(name = "genre", isRequired = true)),
                ),
                AddonCatalog(
                    type = "movie",
                    id = "search-required",
                    name = "Search required",
                    extra = listOf(AddonExtraProperty(name = "search", isRequired = true)),
                ),
                AddonCatalog(
                    type = "movie",
                    id = "other-required",
                    name = "Other required",
                    extra = listOf(AddonExtraProperty(name = "region", isRequired = true)),
                ),
                AddonCatalog(
                    type = "anime",
                    id = "paged",
                    name = "Paged",
                    extra = listOf(AddonExtraProperty(name = "skip")),
                ),
            ),
        )

        val sources = SearchRepository.buildDiscoverSources(
            addons = listOf(addon, ManagedAddon(manifestUrl = "https://pending.example/manifest.json")),
        )

        assertEquals(
            listOf(
                "discover:movie:featured",
                "discover:series:genre",
                "discover:anime:paged",
            ),
            sources.map { it.key },
        )
        assertEquals(listOf("Action", "Drama"), sources[1].genreOptions)
        assertTrue(sources[1].genreRequired)
        assertTrue(sources[2].supportsPagination)
        assertEquals("Discover addon", sources[0].addonName)
        assertEquals(addon.manifestUrl, sources[0].manifestUrl)
    }

    private fun managedAddon(
        id: String,
        name: String,
        catalogs: List<AddonCatalog>,
    ): ManagedAddon {
        val manifestUrl = "https://$id.example/manifest.json"
        return ManagedAddon(
            manifestUrl = manifestUrl,
            manifest = AddonManifest(
                id = id,
                name = name,
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series", "anime"))),
                types = listOf("movie", "series", "anime"),
                catalogs = catalogs,
                transportUrl = manifestUrl,
            ),
        )
    }

    private fun discoverCatalog(key: String, type: String): DiscoverCatalogOption =
        DiscoverCatalogOption(
            key = key,
            addonName = "Addon",
            manifestUrl = "https://example.com/manifest.json",
            type = type,
            catalogId = key,
            catalogName = key,
        )
}
