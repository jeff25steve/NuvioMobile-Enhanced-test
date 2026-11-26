package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFramePlaybackContextTest {
    @Test
    fun beginAttemptPublishesImmutableFrameContext() {
        val store = VideoFramePlaybackContextStore()

        store.beginAttempt(
            attempt = 4,
            expectedMediaIdentity = "id:episode-4",
            currentMediaIdentity = "id:episode-4",
            playbackPositionMs = 1_250L,
        )

        assertEquals(
            VideoFramePlaybackContext(
                attempt = 4,
                expectedMediaIdentity = "id:episode-4",
                currentMediaIdentity = "id:episode-4",
                playbackPositionMs = 1_250L,
            ),
            store.snapshot(),
        )
        assertTrue(store.snapshot().isCurrentMedia)
    }

    @Test
    fun playerStateUpdatesPreserveAttemptAndExpectedIdentity() {
        val store = VideoFramePlaybackContextStore()
        store.beginAttempt(
            attempt = 7,
            expectedMediaIdentity = "id:episode-7",
            currentMediaIdentity = "id:episode-6",
            playbackPositionMs = 0L,
        )

        store.updatePlayerState(
            currentMediaIdentity = "id:episode-7",
            playbackPositionMs = 3_500L,
        )

        assertEquals(
            VideoFramePlaybackContext(
                attempt = 7,
                expectedMediaIdentity = "id:episode-7",
                currentMediaIdentity = "id:episode-7",
                playbackPositionMs = 3_500L,
            ),
            store.snapshot(),
        )
        assertTrue(store.snapshot().isCurrentMedia)
    }

    @Test
    fun staleCurrentMediaIsNotAcceptedForCurrentAttempt() {
        val store = VideoFramePlaybackContextStore()
        store.beginAttempt(
            attempt = 8,
            expectedMediaIdentity = "id:episode-8",
            currentMediaIdentity = "id:episode-7",
            playbackPositionMs = 9_000L,
        )

        assertFalse(store.snapshot().isCurrentMedia)
    }
}
