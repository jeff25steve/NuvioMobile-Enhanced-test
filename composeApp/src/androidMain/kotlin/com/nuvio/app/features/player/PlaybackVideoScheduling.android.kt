package com.nuvio.app.features.player

import java.util.concurrent.atomic.AtomicReference

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }

internal fun addDroppedVideoFrameCount(currentCount: Long, droppedFrames: Int): Long {
    val current = currentCount.coerceAtLeast(0L)
    if (droppedFrames <= 0) return current
    val increment = droppedFrames.toLong()
    return if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment
}

/**
 * Immutable player state copied on the player's application thread for frame callbacks.
 *
 * VideoFrameMetadataListener runs on ExoPlayer's internal playback thread. It must not query
 * Player properties directly; this snapshot lets it consume the most recently published state
 * without crossing Media3's application-thread boundary.
 */
internal data class VideoFramePlaybackContext(
    val attempt: Int = 0,
    val expectedMediaIdentity: String? = null,
    val currentMediaIdentity: String? = null,
    val playbackPositionMs: Long = 0L,
) {
    val isCurrentMedia: Boolean
        get() = expectedMediaIdentity != null && expectedMediaIdentity == currentMediaIdentity
}

/**
 * Single-writer (application thread), multi-reader (playback thread) snapshot hand-off.
 */
internal class VideoFramePlaybackContextStore {
    private val context = AtomicReference(VideoFramePlaybackContext())

    fun beginAttempt(
        attempt: Int,
        expectedMediaIdentity: String?,
        currentMediaIdentity: String?,
        playbackPositionMs: Long,
    ) {
        context.set(
            VideoFramePlaybackContext(
                attempt = attempt,
                expectedMediaIdentity = expectedMediaIdentity,
                currentMediaIdentity = currentMediaIdentity,
                playbackPositionMs = playbackPositionMs.coerceAtLeast(0L),
            ),
        )
    }

    fun updatePlayerState(currentMediaIdentity: String?, playbackPositionMs: Long) {
        context.set(
            context.get().copy(
                currentMediaIdentity = currentMediaIdentity,
                playbackPositionMs = playbackPositionMs.coerceAtLeast(0L),
            ),
        )
    }

    fun snapshot(): VideoFramePlaybackContext = context.get()
}
