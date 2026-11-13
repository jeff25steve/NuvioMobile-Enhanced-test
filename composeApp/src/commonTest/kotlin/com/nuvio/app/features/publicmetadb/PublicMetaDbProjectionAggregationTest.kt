package com.nuvio.app.features.publicmetadb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicMetaDbProjectionAggregationTest {
    @Test
    fun `next up selects the newest positive season even when a special is newer`() {
        val projection = PublicMetaDbProjection(
            PublicMetaDbSyncSnapshot(
                watched = listOf(
                    PublicMetaDbWatchedEntry(
                        tmdbId = 42L,
                        mediaType = "tv",
                        season = 0,
                        episode = 5,
                        watchedAtEpochMs = 900L,
                    ),
                    PublicMetaDbWatchedEntry(
                        tmdbId = 42L,
                        mediaType = "tv",
                        season = 1,
                        episode = 1,
                        watchedAtEpochMs = 100L,
                    ),
                    PublicMetaDbWatchedEntry(
                        tmdbId = 42L,
                        mediaType = "tv",
                        season = 1,
                        episode = 6,
                        watchedAtEpochMs = 200L,
                    ),
                    PublicMetaDbWatchedEntry(
                        tmdbId = 42L,
                        mediaType = "movie",
                        season = 9,
                        episode = 99,
                        watchedAtEpochMs = 2_000L,
                    ),
                ),
            ),
        )

        val nextUp = projection.continueWatching.single()

        assertEquals("tmdb:42", nextUp.parentMetaId)
        assertEquals(1, nextUp.seasonNumber)
        assertEquals(6, nextUp.episodeNumber)
        assertEquals(200L, nextUp.lastUpdatedEpochMs)
    }

    @Test
    fun `next up falls back to the newest entry when no positive season exists`() {
        val projection = PublicMetaDbProjection(
            PublicMetaDbSyncSnapshot(
                watched = listOf(
                    PublicMetaDbWatchedEntry(
                        tmdbId = 77L,
                        mediaType = "tv",
                        season = 0,
                        episode = 1,
                        watchedAtEpochMs = 100L,
                    ),
                    PublicMetaDbWatchedEntry(
                        tmdbId = 77L,
                        mediaType = "tv",
                        season = 0,
                        episode = 2,
                        watchedAtEpochMs = 200L,
                    ),
                    PublicMetaDbWatchedEntry(
                        tmdbId = 77L,
                        mediaType = "tv",
                        season = -1,
                        episode = 3,
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
    fun `an existing show resume point suppresses next up even when playback is below threshold`() {
        val projection = PublicMetaDbProjection(
            PublicMetaDbSyncSnapshot(
                watched = listOf(
                    PublicMetaDbWatchedEntry(
                        tmdbId = 88L,
                        mediaType = "tv",
                        season = 1,
                        episode = 4,
                        watchedAtEpochMs = 400L,
                    ),
                ),
                resume = listOf(
                    PublicMetaDbResumePoint(
                        tmdbId = 88L,
                        mediaType = "tv",
                        season = 1,
                        episode = 4,
                        progressPercent = 0.5,
                        updatedAtEpochMs = 500L,
                    ),
                ),
            ),
        )

        assertTrue(projection.continueWatching.isEmpty())
    }
}
