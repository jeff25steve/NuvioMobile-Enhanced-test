package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackVideoSchedulingTest {
    @Test
    fun frameProcessingOffsetReportsAnAverageOnlyWhenFramesWereMeasured() {
        assertEquals(250L, averageVideoFrameProcessingOffsetUs(1_000L, 4))
        assertEquals(-250L, averageVideoFrameProcessingOffsetUs(-1_000L, 4))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, 0))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, -1))
    }

    @Test
    fun droppedFrameCounterIsMonotonicAndSaturates() {
        assertEquals(12L, addDroppedVideoFrameCount(10L, 2))
        assertEquals(10L, addDroppedVideoFrameCount(10L, -1))
        assertEquals(Long.MAX_VALUE, addDroppedVideoFrameCount(Long.MAX_VALUE - 1L, 2))
    }
}
