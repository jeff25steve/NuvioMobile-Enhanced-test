package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerQualitySelectionStateTest {
    private val sourceUrl = "https://example.com/master.m3u8?token=abc"

    @Test
    fun hlsQualityInspectionWaitsForNativePlayerReadiness() {
        val pending = PlayerQualitySelectionState(
            isLoading = true,
            sourceUrl = sourceUrl,
        )

        assertFalse(
            shouldResolveHlsQualityAfterPlaybackReady(
                nativeAdaptivePlayback = false,
                initialLoadCompleted = true,
                qualityState = pending,
            ),
        )
        assertFalse(
            shouldResolveHlsQualityAfterPlaybackReady(
                nativeAdaptivePlayback = true,
                initialLoadCompleted = false,
                qualityState = pending,
            ),
        )
        assertTrue(
            shouldResolveHlsQualityAfterPlaybackReady(
                nativeAdaptivePlayback = true,
                initialLoadCompleted = true,
                qualityState = pending,
            ),
        )
    }

    @Test
    fun completedQualityInspectionIsNotRepeatedAfterPlayerReload() {
        val resolved = PlayerQualitySelectionState(
            sourceUrl = sourceUrl,
            isResolved = true,
        )

        assertFalse(
            shouldResolveHlsQualityAfterPlaybackReady(
                nativeAdaptivePlayback = true,
                initialLoadCompleted = true,
                qualityState = resolved,
            ),
        )
    }

    @Test
    fun sourceWithoutAnHlsInspectionCanStartAsAlreadyResolved() {
        val resolved = PlayerQualitySelectionState(
            sourceUrl = "https://example.com/movie.mp4",
            isResolved = true,
        )

        assertFalse(resolved.isLoading)
        assertFalse(
            shouldResolveHlsQualityAfterPlaybackReady(
                nativeAdaptivePlayback = false,
                initialLoadCompleted = true,
                qualityState = resolved,
            ),
        )
    }
}
