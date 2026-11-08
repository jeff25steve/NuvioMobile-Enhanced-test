package com.nuvio.app.features.tracking

import com.nuvio.app.features.player.skip.SimklIdResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingAnimeIdMapperTest {
    @Test
    fun `episode mapping returns the first exact match regardless of list order`() {
        val firstExact = SimklIdResolver.EpisodeMapping(
            animeEpisode = 5,
            tvdbSeason = 1,
            tvdbEpisode = 5,
        )
        val secondExact = firstExact.copy(tvdbSeason = 2, tvdbEpisode = 1)
        val mappings = listOf(
            SimklIdResolver.EpisodeMapping(7, 2, 3),
            firstExact,
            secondExact,
            SimklIdResolver.EpisodeMapping(3, 1, 3),
        )

        assertEquals(firstExact, resolveAnimeEpisodeMapping(mappings, 5))
    }

    @Test
    fun `episode mapping falls back to greatest earlier anime episode and keeps first tie`() {
        val firstGreatest = SimklIdResolver.EpisodeMapping(
            animeEpisode = 4,
            tvdbSeason = 3,
            tvdbEpisode = 4,
        )
        val laterTie = firstGreatest.copy(tvdbSeason = 8, tvdbEpisode = 1)
        val mappings = listOf(
            SimklIdResolver.EpisodeMapping(9, 1, 9),
            SimklIdResolver.EpisodeMapping(2, 1, 2),
            firstGreatest,
            laterTie,
            SimklIdResolver.EpisodeMapping(1, 1, 1),
        )

        assertEquals(firstGreatest, resolveAnimeEpisodeMapping(mappings, 6))
    }

    @Test
    fun `episode mapping has no fallback when every mapped episode is later`() {
        assertNull(
            resolveAnimeEpisodeMapping(
                episodes = listOf(
                    SimklIdResolver.EpisodeMapping(7, 1, 7),
                    SimklIdResolver.EpisodeMapping(8, 1, 8),
                ),
                number = 5,
            ),
        )
        assertNull(resolveAnimeEpisodeMapping(emptyList(), 5))
    }
}
