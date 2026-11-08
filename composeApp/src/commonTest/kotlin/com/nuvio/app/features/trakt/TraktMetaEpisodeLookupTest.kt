package com.nuvio.app.features.trakt

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TraktMetaEpisodeLookupTest {

    @Test
    fun `episode coordinate index preserves first matching video`() {
        val first = MetaVideo(
            id = "episode-first",
            title = "First copy",
            season = 1,
            episode = 2,
        )
        val duplicate = MetaVideo(
            id = "episode-duplicate",
            title = "Later duplicate",
            season = 1,
            episode = 2,
        )
        val indexed = indexMetaVideosByEpisode(listOf(first, duplicate))

        assertSame(first, indexed[1 to 2])
        assertEquals(1, indexed.size)
    }

    @Test
    fun `episode coordinate index ignores incomplete coordinates`() {
        val missingSeason = MetaVideo(
            id = "missing-season",
            title = "Missing season",
            episode = 1,
        )
        val missingEpisode = MetaVideo(
            id = "missing-episode",
            title = "Missing episode",
            season = 1,
        )
        val indexed = indexMetaVideosByEpisode(listOf(missingSeason, missingEpisode))

        assertEquals(emptyMap(), indexed)
        assertNull(indexed[1 to 1])
    }
}
