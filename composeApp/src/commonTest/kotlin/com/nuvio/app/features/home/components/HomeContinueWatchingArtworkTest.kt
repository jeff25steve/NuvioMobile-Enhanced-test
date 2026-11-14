package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.DpSize
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeContinueWatchingArtworkTest {
    @Test
    fun inProgressEpisodeThumbnailRemainsBlurredUntilWatched() {
        val item = item(progressFraction = 0.5f)

        assertTrue(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "thumb.jpg",
            ),
        )
    }

    @Test
    fun completedEpisodeThumbnailIsNotBlurred() {
        val item = item(progressFraction = 0.9f)

        assertFalse(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "thumb.jpg",
            ),
        )
    }

    @Test
    fun fallbackArtworkIsNotBlurred() {
        val item = item(progressFraction = 0.5f)

        assertFalse(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "backdrop.jpg",
            ),
        )
    }

    @Test
    fun cardPrefetchTargetUsesTheDisplayedBackdropAndRenderedSize() {
        val posterStyle = PosterCardStyleUiState()
        val layout = rememberContinueWatchingLayout(390f, posterStyle)
        val item = item(progressFraction = 0f).copy(
            isNextUp = true,
            background = "backdrop.jpg",
            rawBackgroundUrl = "raw-backdrop.jpg",
        )

        val target = item.toPosterPrefetchTarget(
            style = ContinueWatchingSectionStyle.Card,
            layout = layout,
            basePosterWidthDp = posterStyle.widthDp,
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = true,
        )

        assertEquals("backdrop.jpg", target?.imageUrl)
        assertEquals("raw-backdrop.jpg", target?.fallbackImageUrl)
        assertEquals(
            DpSize(
                continueWatchingLandscapeCardWidth(posterStyle.widthDp),
                continueWatchingLandscapeCardHeight(posterStyle.widthDp),
            ),
            target?.sizeDp,
        )
        assertEquals(NuvioPosterShape.Landscape, target?.shape)
    }

    @Test
    fun widePrefetchTargetUsesArtworkPanelBounds() {
        val posterStyle = PosterCardStyleUiState()
        val layout = rememberContinueWatchingLayout(390f, posterStyle)
        val item = item(progressFraction = 0.2f).copy(
            poster = null,
            background = "backdrop.jpg",
            rawBackgroundUrl = "raw-backdrop.jpg",
        )

        val target = item.toPosterPrefetchTarget(
            style = ContinueWatchingSectionStyle.Wide,
            layout = layout,
            basePosterWidthDp = posterStyle.widthDp,
            useEpisodeThumbnails = false,
            preferBackdropForNextUp = false,
        )

        assertEquals("backdrop.jpg", target?.imageUrl)
        assertEquals("raw-backdrop.jpg", target?.fallbackImageUrl)
        assertEquals(DpSize(layout.widePosterStripWidth, layout.wideCardHeight), target?.sizeDp)
        assertEquals(NuvioPosterShape.Landscape, target?.shape)
    }

    @Test
    fun posterPrefetchTargetUsesPosterBoundsAndOriginalFallback() {
        val posterStyle = PosterCardStyleUiState()
        val layout = rememberContinueWatchingLayout(390f, posterStyle)
        val item = item(progressFraction = 0.2f).copy(rawPosterUrl = "raw-poster.jpg")

        val target = item.toPosterPrefetchTarget(
            style = ContinueWatchingSectionStyle.Poster,
            layout = layout,
            basePosterWidthDp = posterStyle.widthDp,
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = false,
        )

        assertEquals("poster.jpg", target?.imageUrl)
        assertEquals("raw-poster.jpg", target?.fallbackImageUrl)
        assertEquals(DpSize(layout.posterCardWidth, layout.posterCardHeight), target?.sizeDp)
        assertEquals(NuvioPosterShape.Poster, target?.shape)
    }

    @Test
    fun missingContinueWatchingArtworkDoesNotCreatePrefetchTarget() {
        val item = item(progressFraction = 0.2f).copy(
            poster = null,
            background = null,
            episodeThumbnail = null,
            imageUrl = null,
        )

        val target = item.toPosterPrefetchTarget(
            style = ContinueWatchingSectionStyle.Poster,
            layout = rememberContinueWatchingLayout(390f),
            basePosterWidthDp = PosterCardStyleUiState().widthDp,
            useEpisodeThumbnails = false,
            preferBackdropForNextUp = false,
        )

        assertEquals(null, target)
    }

    private fun item(progressFraction: Float) = ContinueWatchingItem(
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:1",
        title = "Show",
        subtitle = "S1 E1",
        imageUrl = "thumb.jpg",
        poster = "poster.jpg",
        background = "backdrop.jpg",
        seasonNumber = 1,
        episodeNumber = 1,
        episodeThumbnail = "thumb.jpg",
        resumePositionMs = 300_000L,
        durationMs = 600_000L,
        progressFraction = progressFraction,
    )
}
