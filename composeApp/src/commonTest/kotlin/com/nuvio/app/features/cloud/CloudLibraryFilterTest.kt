package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudLibraryFilterTest {
    @Test
    fun `projection preserves provider order and searches item names and nested file names`() {
        val providers = listOf(
            provider(
                DebridProviders.Torbox,
                listOf(
                    item(DebridProviders.Torbox, "name-match", CloudLibraryItemType.Torrent, "Needle in title"),
                    item(
                        DebridProviders.Torbox,
                        "file-match",
                        CloudLibraryItemType.Usenet,
                        "Archive",
                        files = listOf(CloudLibraryFile(id = "file", name = "needle_episode.mkv")),
                    ),
                    item(DebridProviders.Torbox, "no-match", CloudLibraryItemType.File, "Archive"),
                ),
            ),
            provider(
                DebridProviders.Premiumize,
                listOf(item(DebridProviders.Premiumize, "other-provider", CloudLibraryItemType.Torrent, "Needle elsewhere")),
            ),
        )

        val projection = projectCloudLibraryItems(
            providers = providers,
            selectedProviderId = DebridProviders.TORBOX_ID,
            selectedType = null,
            searchQuery = " NEEDLE ",
        )

        assertEquals(
            listOf(CloudLibraryItemType.Torrent, CloudLibraryItemType.Usenet, CloudLibraryItemType.File),
            projection.availableTypes,
        )
        assertNull(projection.effectiveSelectedType)
        assertEquals("NEEDLE", projection.trimmedQuery)
        assertEquals(listOf("name-match", "file-match"), projection.filteredItems.map { it.id })
    }

    @Test
    fun `projection ignores a selected type unavailable for the selected provider`() {
        val providers = listOf(
            provider(
                DebridProviders.Torbox,
                listOf(
                    item(DebridProviders.Torbox, "torrent", CloudLibraryItemType.Torrent, "Torrent"),
                    item(DebridProviders.Torbox, "usenet", CloudLibraryItemType.Usenet, "Usenet"),
                ),
            ),
            provider(
                DebridProviders.Premiumize,
                listOf(item(DebridProviders.Premiumize, "web", CloudLibraryItemType.WebDownload, "Web download")),
            ),
        )

        val projection = projectCloudLibraryItems(
            providers = providers,
            selectedProviderId = DebridProviders.TORBOX_ID,
            selectedType = CloudLibraryItemType.WebDownload,
            searchQuery = "",
        )

        assertEquals(listOf(CloudLibraryItemType.Torrent, CloudLibraryItemType.Usenet), projection.availableTypes)
        assertNull(projection.effectiveSelectedType)
        assertEquals(listOf("torrent", "usenet"), projection.filteredItems.map { it.id })
    }

    @Test
    fun `projection applies a valid type after provider filtering and preserves source order`() {
        val providers = listOf(
            provider(
                DebridProviders.Torbox,
                listOf(
                    item(DebridProviders.Torbox, "first", CloudLibraryItemType.Torrent, "First"),
                    item(DebridProviders.Torbox, "second", CloudLibraryItemType.Usenet, "Second"),
                    item(DebridProviders.Torbox, "third", CloudLibraryItemType.Usenet, "Third"),
                ),
            ),
        )

        val projection = projectCloudLibraryItems(
            providers = providers,
            selectedProviderId = DebridProviders.TORBOX_ID,
            selectedType = CloudLibraryItemType.Usenet,
            searchQuery = "",
        )

        assertEquals(CloudLibraryItemType.Usenet, projection.effectiveSelectedType)
        assertEquals(listOf("second", "third"), projection.filteredItems.map { it.id })
    }

    @Test
    fun `selected item reconciliation searches provider lists and preserves keys while refreshing`() {
        val state = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(
                provider(DebridProviders.Torbox, listOf(item(DebridProviders.Torbox, "saved", CloudLibraryItemType.Torrent, "Saved"))),
            ),
        )

        assertEquals(
            "torbox:Torrent:saved",
            reconcileCloudLibrarySelectedItemKey("torbox:Torrent:saved", state),
        )
        assertNull(reconcileCloudLibrarySelectedItemKey("missing", state))
        assertEquals(
            "missing",
            reconcileCloudLibrarySelectedItemKey("missing", state.copy(isRefreshing = true)),
        )
    }

    private fun provider(
        provider: com.nuvio.app.features.debrid.DebridProvider,
        items: List<CloudLibraryItem>,
    ): CloudLibraryProviderState = CloudLibraryProviderState(provider = provider, items = items)

    private fun item(
        provider: com.nuvio.app.features.debrid.DebridProvider,
        id: String,
        type: CloudLibraryItemType,
        name: String,
        files: List<CloudLibraryFile> = emptyList(),
    ): CloudLibraryItem = CloudLibraryItem(
        providerId = provider.id,
        providerName = provider.displayName,
        id = id,
        type = type,
        name = name,
        files = files,
    )
}
