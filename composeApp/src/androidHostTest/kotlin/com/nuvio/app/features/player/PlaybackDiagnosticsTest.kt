package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackDiagnosticsTest {

    @Test
    fun mediaIdentityPrefersStableMediaIdAndFallsBackToUri() {
        val identified = MediaItem.Builder()
            .setMediaId("episode-42")
            .setUri("https://example.test/video.mkv")
            .build()
        val uriOnly = MediaItem.fromUri("https://example.test/other.mkv")

        assertEquals("id:episode-42", playbackDiagnosticMediaIdentity(identified))
        assertEquals("uri:https://example.test/other.mkv", playbackDiagnosticMediaIdentity(uriOnly))
        assertEquals(null, playbackDiagnosticMediaIdentity(null))
    }

    @Test
    fun bufferedDurationIsMeasuredAheadOfTheCurrentPlayhead() {
        assertEquals(12_000L, bufferedAheadDurationMs(8_000L, 20_000L))
        assertEquals(0L, bufferedAheadDurationMs(20_000L, 8_000L))
        assertEquals(0L, bufferedAheadDurationMs(-1L, -1L))
    }

    @Test
    fun firstLoadsAreTrackedSeparatelyByMediaTrackAndTask() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 1L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 2L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 3L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 4L))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 5L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, 6L))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_DRM, C.TRACK_TYPE_UNKNOWN, 7L))
    }

    @Test
    fun firstCompletionMustMatchTheFirstLoadTaskForItsTrack() {
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
    fun rebufferLoadsAndCompletionsAreTrackedPerTrackAndTask() {
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
        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, 8L))

        diagnostics.markReady()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
        diagnostics.markReady()
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
        assertFalse(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 23L))
    }

    @Test
    fun beginningANewAttemptResetsRebufferTaskCorrelation() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        diagnostics.markPlaybackStarted()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 30L))

        diagnostics.beginAttempt(250L)

        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, 31L))
    }

    @Test
    fun beginningANewAttemptResetsFirstLoadAndCompletionTaskIds() {
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

        assertFalse(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 300L, loadDurationMs = 150L))
        assertTrue(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 300L, loadDurationMs = 50L))
        assertFalse(diagnostics.isLoadForCurrentAttempt(loadEventElapsedRealtimeMs = 240L, loadDurationMs = 0L))
    }

    @Test
    fun loadEventsMustMatchTheMediaItemOwnedByThePrepareAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(250L, expectedMediaIdentity = "id:episode-2")

        assertTrue(
            diagnostics.isLoadForCurrentAttempt(
                loadEventElapsedRealtimeMs = 350L,
                loadDurationMs = 50L,
                eventMediaIdentity = "id:episode-2",
            ),
        )
        assertFalse(
            diagnostics.isLoadForCurrentAttempt(
                loadEventElapsedRealtimeMs = 350L,
                loadDurationMs = 50L,
                eventMediaIdentity = "id:episode-3",
            ),
        )
        assertFalse(
            diagnostics.isLoadForCurrentAttempt(
                loadEventElapsedRealtimeMs = 350L,
                loadDurationMs = 50L,
                eventMediaIdentity = null,
            ),
        )
    }

    @Test
    fun droppedVideoFrameCountRemainsSourceScopedAcrossPrepareAttempts() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertEquals(3L, diagnostics.recordDroppedVideoFrames(3))
        assertEquals(3L, diagnostics.recordDroppedVideoFrames(0))
        assertEquals(3L, diagnostics.recordDroppedVideoFrames(-1))
        assertEquals(8L, diagnostics.recordDroppedVideoFrames(5))
        assertEquals(8L, diagnostics.droppedVideoFrameCount)

        diagnostics.beginAttempt(250L)

        // Analytics callbacks can be delivered after a new prepare attempt begins.
        // Keep this counter scoped to the player/source session, not the current attempt.
        assertEquals(8L, diagnostics.droppedVideoFrameCount)
        assertEquals(10L, diagnostics.recordDroppedVideoFrames(2))
    }

    @Test
    fun droppedVideoFrameCountSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, addDroppedVideoFrameCount(Long.MAX_VALUE - 1L, 2))
        assertEquals(Long.MAX_VALUE, addDroppedVideoFrameCount(Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(4L, addDroppedVideoFrameCount(4L, -1))
        assertEquals(0L, addDroppedVideoFrameCount(0L, 0))
    }

    @Test
    fun decoderFailureCanFallBackInEitherConfiguredRendererPriorityDirection() {
        assertEquals(
            androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            decoderPriorityFallbackAfterFailure(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                isDecoderFailure = true,
            ),
        )
        assertEquals(
            androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            decoderPriorityFallbackAfterFailure(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                isDecoderFailure = true,
            ),
        )
        assertEquals(
            null,
            decoderPriorityFallbackAfterFailure(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                isDecoderFailure = true,
            ),
        )
        assertEquals(
            null,
            decoderPriorityFallbackAfterFailure(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                isDecoderFailure = true,
            ),
        )
        assertEquals(
            null,
            decoderPriorityFallbackAfterFailure(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                isDecoderFailure = false,
            ),
        )
    }

    @Test
    fun subtitleRestoreWaitsForTextTracksButCanRestoreDisabledStateImmediately() {
        assertFalse(shouldApplyPendingSubtitleTrackSelection(2, hasTrackGroups = true, hasTextTracks = false))
        assertTrue(shouldApplyPendingSubtitleTrackSelection(2, hasTrackGroups = true, hasTextTracks = true))
        assertTrue(shouldApplyPendingSubtitleTrackSelection(-1, hasTrackGroups = true, hasTextTracks = false))
        assertFalse(shouldApplyPendingSubtitleTrackSelection(-1, hasTrackGroups = false, hasTextTracks = false))
    }


    @Test
    fun nonDefaultPlaybackSpeedIsRestoredAfterPlayerRebuild() {
        assertEquals(1.5f, playbackSpeedToRestoreAfterRebuild(1.5f))
        assertEquals(0.75f, playbackSpeedToRestoreAfterRebuild(0.75f))
        assertEquals(null, playbackSpeedToRestoreAfterRebuild(1.0f))
        assertEquals(null, playbackSpeedToRestoreAfterRebuild(0.0f))
        assertEquals(null, playbackSpeedToRestoreAfterRebuild(Float.NaN))
        assertEquals(null, playbackSpeedToRestoreAfterRebuild(Float.POSITIVE_INFINITY))
    }


    @Test
    fun subtitleRestoreOnlyDisablesTracksWhenTheyWereExplicitlyDisabled() {
        assertEquals(4, subtitleTrackIndexForReload(4, hasTextTracks = true, textTrackTypeDisabled = false))
        assertEquals(-1, subtitleTrackIndexForReload(-1, hasTextTracks = true, textTrackTypeDisabled = true))
        assertEquals(null, subtitleTrackIndexForReload(-1, hasTextTracks = true, textTrackTypeDisabled = false))
        assertEquals(null, subtitleTrackIndexForReload(-1, hasTextTracks = false, textTrackTypeDisabled = true))
    }

}
