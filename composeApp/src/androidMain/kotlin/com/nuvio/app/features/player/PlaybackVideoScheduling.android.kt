package com.nuvio.app.features.player

/**
 * Media3 1.8.0 does not expose the process-wide renderer Flags registry introduced later.
 * Keep existing setup call sites source-compatible; renderer scheduling remains on the
 * supported 1.8 default path until Nuvio's custom AARs are rebuilt against newer Media3.
 */
internal fun enableMedia3VideoRendererDurationToProgress() = Unit

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }

internal fun addDroppedVideoFrameCount(currentCount: Long, droppedFrames: Int): Long {
    val current = currentCount.coerceAtLeast(0L)
    if (droppedFrames <= 0) return current
    val increment = droppedFrames.toLong()
    return if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment
}
