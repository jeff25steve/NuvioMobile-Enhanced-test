@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class,
)

package com.nuvio.app.features.player

import androidx.media3.common.Flags

/**
 * Enables Media3 1.12's renderer-aware dynamic scheduling before player construction.
 *
 * The 1.12 beta moved this from DefaultRenderersFactory to the process-wide Flags registry.
 * It is effective with dynamic scheduling and asynchronous MediaCodec output; on devices that
 * use synchronous codec operation it leaves the normal renderer scheduling path unchanged.
 */
internal fun enableMedia3VideoRendererDurationToProgress() {
    Flags.enableFlag(Flags.FLAG_VIDEO_RENDERER_DURATION_TO_PROGRESS)
}

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }

internal fun addDroppedVideoFrameCount(currentCount: Long, droppedFrames: Int): Long {
    val current = currentCount.coerceAtLeast(0L)
    if (droppedFrames <= 0) return current
    val increment = droppedFrames.toLong()
    return if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment
}
