package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStallResumeTest {

    @Test
    fun closedStreamAfterPlaybackKeepsTheStalledPositionForTheNextPrepare() {
        val initialPositionMs = 1_193_023L
        val stalledPositionMs = 1_245_041L
        val session = PlaybackReopenSession(initialPositionMs)

        assertEquals(initialPositionMs, session.prepareStartPositionMs())
        session.onReady()

        val reopened = session.onFailure(
            failure = PlaybackSourceFailure.ClosedStream,
            positionMs = stalledPositionMs,
            durationMs = 2_721_803L,
        )

        assertTrue(reopened)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertTrue(session.probeAttempted)

        val adoptedRewoundPosition = session.onFailure(
            failure = PlaybackSourceFailure.ReadPositionOutOfRange,
            positionMs = initialPositionMs,
            durationMs = 2_721_803L,
        )

        assertFalse(adoptedRewoundPosition)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertFalse(
            useVodCacheForSource(
                vodCacheEnabled = true,
                youtubeChunked = false,
                adaptive = false,
                loopback = true,
            ),
        )
    }

    @Test
    fun stuckPlayerAfterPlaybackReopensAtTheCurrentPositionOnlyOnce() {
        val session = PlaybackReopenSession(initialPositionMs = 30_000L)
        session.onReady()

        assertTrue(
            session.onFailure(
                failure = PlaybackSourceFailure.StuckPlayback,
                positionMs = 47_500L,
                durationMs = 180_000L,
            ),
        )
        assertEquals(47_500L, session.prepareStartPositionMs())
        assertFalse(
            session.onFailure(
                failure = PlaybackSourceFailure.StuckPlayback,
                positionMs = 48_000L,
                durationMs = 180_000L,
            ),
        )
        assertEquals(47_500L, session.prepareStartPositionMs())
    }

    @Test
    fun stuckPlayerWithoutKnownVodDurationDoesNotReopen() {
        val session = PlaybackReopenSession(initialPositionMs = 0L)

        assertFalse(
            session.onFailure(
                failure = PlaybackSourceFailure.StuckPlayback,
                positionMs = 12_000L,
                durationMs = 0L,
            ),
        )
        assertEquals(0L, session.resumeGeneration.toLong())
    }

    @Test
    fun startupFailureDoesNotReplaceTheInitialPosition() {
        val session = PlaybackReopenSession(initialPositionMs = 1_193_023L)

        val reopened = session.onFailure(
            failure = PlaybackSourceFailure.ClosedStream,
            positionMs = 0L,
            durationMs = 0L,
        )

        assertFalse(reopened)
        assertEquals(1_193_023L, session.prepareStartPositionMs())
        assertFalse(session.probeAttempted)
    }
}
