package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class HomePosterCardFallbackTest {

    private val landscapeStyle = PosterCardStyleUiState(catalogLandscapeModeEnabled = true)

    @Test
    fun `landscape custom poster prefers original backdrop as fallback`() {
        val images = item(banner = "https://img/backdrop.jpg").homePosterImages(landscapeStyle, false)

        assertEquals("https://img/custom-landscape.jpg", images.imageUrl)
        assertEquals("https://img/backdrop.jpg", images.fallbackImageUrl)
        assertEquals(NuvioPosterShape.Landscape, images.shape)
    }

    @Test
    fun `landscape custom poster falls back to original landscape before portrait`() {
        val images = item(banner = " ", rawLandscapePosterUrl = "https://img/original-landscape.jpg")
            .homePosterImages(landscapeStyle, false)

        assertEquals("https://img/original-landscape.jpg", images.fallbackImageUrl)
    }

    @Test
    fun `landscape custom poster falls back to original portrait when no landscape art exists`() {
        val images = item(
            rawPosterUrl = "https://img/original-portrait.jpg",
            rawLandscapePosterUrl = null,
        ).homePosterImages(landscapeStyle, false)

        assertEquals("https://img/original-portrait.jpg", images.fallbackImageUrl)
    }

    @Test
    fun `blank landscape poster does not hide a valid backdrop`() {
        val images = item(
            landscapePoster = " ",
            banner = "https://img/backdrop.jpg",
            rawPosterUrl = null,
            rawLandscapePosterUrl = null,
        ).homePosterImages(landscapeStyle, false)

        assertEquals("https://img/backdrop.jpg", images.imageUrl)
        assertEquals(null, images.fallbackImageUrl)
    }

    private fun item(
        poster: String? = "https://img/poster.jpg",
        banner: String? = null,
        rawPosterUrl: String? = "https://img/raw-poster.jpg",
        landscapePoster: String? = "https://img/custom-landscape.jpg",
        rawLandscapePosterUrl: String? = "https://img/raw-landscape.jpg",
    ) = MetaPreview(
        id = "tt0137523",
        type = "movie",
        name = "Test",
        poster = poster,
        banner = banner,
        rawPosterUrl = rawPosterUrl,
        landscapePoster = landscapePoster,
        rawLandscapePosterUrl = rawLandscapePosterUrl,
    )
}
