package com.nuvio.app.features.player

/** Media3 1.8.0 has no renderer duration-to-progress opt-in; keep this call surface inert on the verified baseline. */
internal fun enableMedia3VideoRendererDurationToProgress() = Unit

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }

internal fun addDroppedVideoFrameCount(currentCount: Long, droppedFrames: Int): Long {
    val current = currentCount.coerceAtLeast(0L)
    if (droppedFrames <= 0) return current
    val increment = droppedFrames.toLong()
    return if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment
}
