package com.nuvio.app.features.wetrakr

import kotlin.test.Test
import kotlin.test.assertEquals

class WeTrakrProjectionAggregationTest {
    @Test
    fun `next up selects the newest positive season ahead of newer specials`() {
        val show = WeTrakrMedia(
            id = 42L,
            type = WeTrakrMediaType.SHOW,
            title = "Example show",
            ids = WeTrakrIds(imdb = "tt0000042", tmdb = 42L),
            runtimeMinutes = 45,
        )
        val projection = WeTrakrProjection(
            WeTrakrSyncSnapshot(
                userId = 1L,
                entries = listOf(
                    WeTrakrListEntry(media = show, status = WeTrakrStatus.WATCHING),
                ),
                watchedEpisodes = listOf(
                    WeTrakrWatchedEpisode(
                        showId = 42L,
                        season = 0,
                        number = 5,
                        watchedAtEpochMs = 900L,
                    ),
                    WeTrakrWatchedEpisode(
                        showId = 42L,
                        season = 1,
                        number = 1,
                        watchedAtEpochMs = 100L,
                    ),
                    WeTrakrWatchedEpisode(
                        showId = 42L,
                        season = 1,
                        number = 6,
                        watchedAtEpochMs = 200L,
                    ),
                ),
            ),
        )

        val nextUp = projection.continueWatching.single()

        assertEquals("tt0000042", nextUp.parentMetaId)
        assertEquals(1, nextUp.seasonNumber)
        assertEquals(6, nextUp.episodeNumber)
        assertEquals(200L, nextUp.lastUpdatedEpochMs)
    }

    @Test
    fun `next up falls back to the newest episode when no positive season exists`() {
        val show = WeTrakrMedia(
            id = 77L,
            type = WeTrakrMediaType.SHOW,
            title = "Specials show",
            ids = WeTrakrIds(imdb = "tt0000077"),
        )
        val projection = WeTrakrProjection(
            WeTrakrSyncSnapshot(
                userId = 1L,
                entries = listOf(
                    WeTrakrListEntry(media = show, status = WeTrakrStatus.WAITING),
                ),
                watchedEpisodes = listOf(
                    WeTrakrWatchedEpisode(
                        showId = 77L,
                        season = 0,
                        number = 1,
                        watchedAtEpochMs = 100L,
                    ),
                    WeTrakrWatchedEpisode(
                        showId = 77L,
                        season = 0,
                        number = 2,
                        watchedAtEpochMs = 200L,
                    ),
                    WeTrakrWatchedEpisode(
                        showId = 77L,
                        season = -1,
                        number = 3,
                        watchedAtEpochMs = 300L,
                    ),
                ),
            ),
        )

        val nextUp = projection.continueWatching.single()

        assertEquals(-1, nextUp.seasonNumber)
        assertEquals(3, nextUp.episodeNumber)
        assertEquals(300L, nextUp.lastUpdatedEpochMs)
    }

    @Test
    fun `duplicate season episode across aliases keeps the first alias match`() {
        val show = WeTrakrMedia(
            id = 42L,
            type = WeTrakrMediaType.SHOW,
            title = "Aliased show",
            ids = WeTrakrIds(imdb = "tt0000042", tmdb = 42L),
        )
        val projection = WeTrakrProjection(
            WeTrakrSyncSnapshot(
                userId = 1L,
                entries = listOf(
                    WeTrakrListEntry(media = show, status = WeTrakrStatus.WATCHING),
                ),
                watchedEpisodes = listOf(
                    WeTrakrWatchedEpisode(
                        showIds = WeTrakrIds(tmdb = 42L),
                        season = 1,
                        number = 3,
                        watchedAtEpochMs = 900L,
                    ),
                    WeTrakrWatchedEpisode(
                        showIds = WeTrakrIds(imdb = "tt0000042"),
                        season = 1,
                        number = 3,
                        watchedAtEpochMs = 100L,
                    ),
                ),
            ),
        )

        val nextUp = projection.continueWatching.single()

        assertEquals(100L, nextUp.lastUpdatedEpochMs)
        assertEquals(1, nextUp.seasonNumber)
        assertEquals(3, nextUp.episodeNumber)
    }
}
