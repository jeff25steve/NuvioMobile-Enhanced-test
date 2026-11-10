package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_catalog_default_title
import nuvio.composeapp.generated.resources.media_movies
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HomeCatalogDefinitionTest {
    private val definition = HomeCatalogDefinition(
        key = "addon:movie:popular",
        defaultTitle = "Popular - Movie",
        catalogName = "Popular",
        addonName = "Addon",
        manifestUrl = "https://example.com/manifest.json",
        type = "movie",
        catalogId = "popular",
        supportsPagination = true,
        descriptorSignature = "signature",
    )

    @Test
    fun `shows the type suffix by default`() {
        assertEquals("Popular - Movie", definition.titleFor(showCatalogType = true))
    }

    @Test
    fun `omits the type suffix when disabled`() {
        assertEquals("Popular", definition.titleFor(showCatalogType = false))
    }

    @Test
    fun `builds localized titles and omits catalogs with required configuration`() = runTest {
        val manifestUrl = "https://example.test/manifest.json"
        val addon = ManagedAddon(
            manifestUrl = manifestUrl,
            manifest = AddonManifest(
                id = "catalog-addon",
                name = "Catalog Addon",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"))),
                types = listOf("movie", "series"),
                catalogs = listOf(
                    AddonCatalog(type = "movie", id = "popular", name = "Popular"),
                    AddonCatalog(
                        type = "series",
                        id = "by-genre",
                        name = "By genre",
                        extra = listOf(AddonExtraProperty(name = "genre", isRequired = true)),
                    ),
                ),
                transportUrl = manifestUrl,
            ),
        )

        val expectedTitle = getString(
            Res.string.home_catalog_default_title,
            "Popular",
            getString(Res.string.media_movies),
        )
        val definitions = buildHomeCatalogDefinitions(listOf(addon))
        val popular = definitions.single { it.key == "catalog-addon:movie:popular" }

        assertEquals(expectedTitle, popular.defaultTitle)
        assertEquals("Popular", popular.titleFor(showCatalogType = false))
        assertFalse(definitions.any { it.key == "catalog-addon:series:by-genre" })
    }

    @Test
    fun `addon refresh signature tracks unresolved terminal and loaded manifest states`() {
        val pendingAddon = ManagedAddon(
            manifestUrl = "https://example.test/manifest.json",
            isRefreshing = true,
        )
        val failedAddon = pendingAddon.copy(
            isRefreshing = false,
            errorMessage = "Timed out",
        )
        val loadedAddon = pendingAddon.copy(
            manifest = AddonManifest(
                id = "addon",
                name = "Addon",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "meta", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = emptyList(),
                transportUrl = pendingAddon.manifestUrl,
            ),
            isRefreshing = false,
        )

        val pendingSignature = buildAddonCatalogRefreshSignature(listOf(pendingAddon))
        val failedSignature = buildAddonCatalogRefreshSignature(listOf(failedAddon))
        val loadedSignature = buildAddonCatalogRefreshSignature(listOf(loadedAddon))

        assertTrue(pendingSignature.isNotEmpty())
        assertNotEquals(pendingSignature, failedSignature)
        assertNotEquals(pendingSignature, loadedSignature)
        assertNotEquals(failedSignature, loadedSignature)
    }
}
