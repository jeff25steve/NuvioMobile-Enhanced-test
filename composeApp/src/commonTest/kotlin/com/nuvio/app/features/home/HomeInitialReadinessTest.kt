package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeInitialReadinessTest {
    @Test
    fun `empty Home waits until source configuration has loaded`() {
        assertTrue(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = false,
                homeCatalogLoading = false,
                homeSourcesLoaded = false,
            ),
        )
    }

    @Test
    fun `cached Home rows can render while source configuration is still loading`() {
        assertFalse(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = true,
                addonManifestsLoading = true,
                homeCatalogLoading = true,
                homeSourcesLoaded = false,
            ),
        )
    }

    @Test
    fun `Home remains loading while addon manifests or catalog requests are pending`() {
        assertTrue(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = true,
                homeCatalogLoading = false,
                homeSourcesLoaded = true,
            ),
        )
        assertTrue(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = false,
                homeCatalogLoading = true,
                homeSourcesLoaded = true,
            ),
        )
    }

    @Test
    fun `loaded empty or error Home can present its intentional empty state`() {
        assertFalse(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = false,
                homeCatalogLoading = false,
                homeSourcesLoaded = true,
            ),
        )
    }
}
