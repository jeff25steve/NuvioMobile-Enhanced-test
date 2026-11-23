package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackVideoSchedulingTest {
    @Test
    fun mediaCodecProgressSchedulingIsEnabledWhenAsynchronousQueueingIsDefault() {
        assertFalse(shouldEnableMediaCodecVideoRendererDurationToProgressUs(30))
        assertTrue(shouldEnableMediaCodecVideoRendererDurationToProgressUs(31))
        assertTrue(shouldEnableMediaCodecVideoRendererDurationToProgressUs(35))
    }

    @Test
    fun frameProcessingOffsetReportsAnAverageOnlyWhenFramesWereMeasured() {
        assertEquals(250L, averageVideoFrameProcessingOffsetUs(1_000L, 4))
        assertEquals(-250L, averageVideoFrameProcessingOffsetUs(-1_000L, 4))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, 0))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, -1))
    }
}
