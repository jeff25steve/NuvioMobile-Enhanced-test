package com.nuvio.app.features.watched

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedModelsTest {
    @Test
    fun `compact watched timestamp normalizes to epoch millis`() {
        val expected = parseZonedIsoDateTimeToEpochMs("2026-04-25T10:02:00Z")

        assertEquals(expected, normalizeWatchedMarkedAtEpochMs(20260425100200L))
    }

    @Test
    fun `epoch watched timestamp is kept unchanged`() {
        assertEquals(1_778_060_222_000L, normalizeWatchedMarkedAtEpochMs(1_778_060_222_000L))
    }

    @Test
    fun `completed playback video ids preserve effective completion semantics`() {
        val entries = listOf(
            progressEntry(videoId = "explicit-complete", isCompleted = true),
            progressEntry(videoId = "percent-complete", progressPercent = 95f),
            progressEntry(videoId = "unfinished"),
            progressEntry(videoId = "explicit-complete"),
        )

        assertEquals(
            setOf("explicit-complete", "percent-complete"),
            completedPlaybackVideoIds(entries),
        )
    }

    @Test
    fun `watched key parsing preserves colon delimited provider ids`() {
        assertEquals(
            "tmdb:123",
            extractContentIdFromWatchedKey("series:tmdb:123:1:2"),
        )
        assertEquals(
            watchedItemKey(type = "series", id = "sibling:456"),
            rebuildWatchedKeyWithSiblingId("series:tmdb:123:1:2", "sibling:456"),
        )
        assertEquals(null, extractContentIdFromWatchedKey("series:tmdb:123:1"))
        assertEquals(null, extractContentIdFromWatchedKey("series::1:2"))
        assertEquals(null, rebuildWatchedKeyWithSiblingId("series:tmdb:123", "sibling"))
    }

    @Test
    fun `remote watched sources carry provider identity`() {
        assertEquals(TrackingProviderId.TRAKT, WatchProgressSource.TRAKT.providerId)
        assertEquals(TrackingProviderId.SIMKL, WatchProgressSource.SIMKL.providerId)
        assertEquals(null, WatchProgressSource.NUVIO_SYNC.providerId)
    }

    private fun progressEntry(
        videoId: String,
        isCompleted: Boolean = false,
        progressPercent: Float? = null,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        lastPositionMs = 0L,
        durationMs = 1_000L,
        lastUpdatedEpochMs = 1L,
        isCompleted = isCompleted,
        progressPercent = progressPercent,
    )
}
