package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRepositoryProjectionTest {

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
