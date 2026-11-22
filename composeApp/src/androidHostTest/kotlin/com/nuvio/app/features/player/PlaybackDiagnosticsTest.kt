package com.nuvio.app.features.player

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticsTest {

    @Test
    fun bufferedDurationIsMeasuredAheadOfTheCurrentPlayhead() {
        assertEquals(12_000L, bufferedAheadDurationMs(8_000L, 20_000L))
        assertEquals(0L, bufferedAheadDurationMs(20_000L, 8_000L))
        assertEquals(0L, bufferedAheadDurationMs(-1L, -1L))
    }

    @Test
    fun firstManifestAndMediaEventsAreLimitedToOnePerAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 1L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 2L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 3L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 4L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 5L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 6L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_DRM))
    }

    @Test
    fun firstCompletionEventsAreLimitedToOnePerAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 10L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 10L))
        assertFalse(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 11L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 12L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 12L))
        assertFalse(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 13L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 14L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 14L))
    }

    @Test
    fun rebufferLoadsAreCapturedOncePerRebufferAfterPlaybackHasStarted() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 31L))
        diagnostics.markPlaybackStarted()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 20L))
        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 21L))
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 22L))
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 20L))
        assertFalse(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 21L))
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 22L))
        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MANIFEST))

        diagnostics.markReady()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
        diagnostics.markReady()
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
        assertFalse(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
    }

    @Test
    fun beginningANewAttemptResetsRebufferDiagnosticState() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        diagnostics.markPlaybackStarted()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 30L))

        diagnostics.beginAttempt(250L)

        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 31L))
    }

    @Test
    fun beginningANewAttemptResetsFirstLoadAndCompletionGuards() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 40L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 41L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 41L))

        diagnostics.beginAttempt(250L)

        assertEquals(2, diagnostics.attempt)
        assertEquals(250L, diagnostics.prepareStartedAtMs)
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 42L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 43L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 42L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 43L))
    }

    @Test
    fun delayedCompletionFromBeforeTheCurrentAttemptIsRejected() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        diagnostics.beginAttempt(250L)

        // The callback is delivered at 300ms, but this load began at 150ms.
        assertFalse(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 300L, loadDurationMs = 150L))
        assertTrue(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 300L, loadDurationMs = 50L))
        assertFalse(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 240L, loadDurationMs = 0L))
    }

    @Test
    fun completionMustMatchTheLoadTaskStartedInTheCurrentAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 50L))

        diagnostics.beginAttempt(250L)

        assertFalse(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 50L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 51L))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 51L))
    }
}
