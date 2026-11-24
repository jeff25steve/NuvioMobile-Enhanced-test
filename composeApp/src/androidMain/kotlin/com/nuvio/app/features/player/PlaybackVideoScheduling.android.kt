package com.nuvio.app.features.player

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }

internal fun addDroppedVideoFrameCount(currentCount: Long, droppedFrames: Int): Long {
    val current = currentCount.coerceAtLeast(0L)
    if (droppedFrames <= 0) return current
    val increment = droppedFrames.toLong()
    return if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment
}
