package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionCatalogSource
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRepositoryProjectionTest {

    @Test
    fun `collection hero sources preserve order and stop at the requested limit`() {
        val first = CollectionSource(addonId = "addon-one", type = "movie", catalogId = "first")
        val second = CollectionSource(addonId = "addon-one", type = "movie", catalogId = "second")
        val ignoredLegacySource = CollectionCatalogSource(
            addonId = "addon-one",
            type = "movie",
            catalogId = "legacy-ignored",
        )
        val catalogFallback = CollectionCatalogSource(
            addonId = "addon-two",
            type = "series",
            catalogId = "third",
            genre = "none",
        )
        val laterSource = CollectionSource(provider = "tmdb", tmdbId = 42, title = "Later source")
        val collections = listOf(
            Collection(
                id = "collection-one",
                title = "First collection",
                folders = listOf(
                    CollectionFolder(
                        id = "folder-one",
                        title = "First folder",
                        sources = listOf(first, second),
                        catalogSources = listOf(ignoredLegacySource),
                    ),
                    CollectionFolder(
                        id = "folder-two",
                        title = "Legacy folder",
                        catalogSources = listOf(catalogFallback),
                    ),
                ),
            ),
            Collection(
                id = "collection-two",
                title = "Second collection",
                folders = listOf(
                    CollectionFolder(
                        id = "folder-three",
                        title = "Later folder",
                        sources = listOf(laterSource),
                    ),
                ),
            ),
        )

        val result = firstCollectionHeroSources(collections, limit = 3)

        assertEquals(
            listOf(
                first,
                second,
                CollectionSource(
                    provider = "addon",
                    addonId = "addon-two",
                    type = "series",
                    catalogId = "third",
                    genre = null,
                ),
            ),
            result,
        )
    }

    @Test
    fun `collection hero source selection returns empty for a zero limit`() {
        val collection = Collection(
            id = "collection",
            title = "Collection",
            folders = listOf(
                CollectionFolder(
                    id = "folder",
                    title = "Folder",
                    sources = listOf(CollectionSource(addonId = "addon", type = "movie", catalogId = "catalog")),
                ),
            ),
        )

        assertEquals(emptyList(), firstCollectionHeroSources(listOf(collection), limit = 0))
    }

    @Test
    fun `hero candidate collection preserves order and first duplicate`() {
        val firstDefinition = definition("first")
        val secondDefinition = definition("second")
        val disabledDefinition = definition("disabled")

        val firstMovie = MetaPreview(id = "shared", type = "movie", name = "First copy")
        val laterMovieDuplicate = MetaPreview(id = "shared", type = "movie", name = "Later copy")
        val sameIdDifferentType = MetaPreview(id = "shared", type = "series", name = "Series")
        val secondMovie = MetaPreview(id = "second", type = "movie", name = "Second")
        val disabledMovie = MetaPreview(id = "disabled", type = "movie", name = "Disabled")

        val sections = mapOf(
            firstDefinition.cacheKey to section("first", listOf(firstMovie)),
            secondDefinition.cacheKey to section(
                "second",
                listOf(laterMovieDuplicate, sameIdDifferentType, secondMovie),
            ),
            disabledDefinition.cacheKey to section("disabled", listOf(disabledMovie)),
        )
        val preferences = mapOf(
            // A disabled row may still be selected as a hero source; preserve that behavior.
            firstDefinition.key to preference(enabled = false, heroSourceEnabled = true),
            secondDefinition.key to preference(enabled = true, heroSourceEnabled = true),
            disabledDefinition.key to preference(enabled = true, heroSourceEnabled = false),
        )
        val requestedDefinitions = mutableListOf<String>()

        val result = collectDistinctHomeHeroItems(
            definitions = listOf(firstDefinition, secondDefinition, disabledDefinition),
            preferences = preferences,
            sectionForDefinition = { definition ->
                requestedDefinitions += definition.key
                sections[definition.cacheKey]
            },
        )

        assertEquals(listOf(firstMovie, sameIdDifferentType, secondMovie), result)
        assertEquals(listOf(firstDefinition.key, secondDefinition.key), requestedDefinitions)
    }

    private fun definition(key: String) = HomeCatalogDefinition(
        key = key,
        defaultTitle = key,
        catalogName = key,
        addonName = "Addon",
        manifestUrl = "https://example.com/manifest.json",
        type = "movie",
        catalogId = key,
        supportsPagination = false,
        descriptorSignature = "signature-$key",
    )

    private fun preference(
        enabled: Boolean,
        heroSourceEnabled: Boolean,
    ) = HomeCatalogPreference(
        customTitle = "",
        enabled = enabled,
        heroSourceEnabled = heroSourceEnabled,
        order = 0,
    )

    private fun section(
        key: String,
        items: List<MetaPreview>,
    ) = HomeCatalogSection(
        key = key,
        title = key,
        subtitle = "Addon",
        addonName = "Addon",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.com/manifest.json",
            contentType = "movie",
            catalogId = key,
            supportsPagination = false,
        ),
        items = items,
    )
}
