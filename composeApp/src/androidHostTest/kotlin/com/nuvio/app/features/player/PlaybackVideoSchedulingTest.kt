@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class,
)

package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.media3.common.Flags
import kotlin.test.assertTrue

class PlaybackVideoSchedulingTest {
    @Test
    fun media3VideoRendererDurationToProgressFlagIsEnabled() {
        enableMedia3VideoRendererDurationToProgress()

        assertTrue(Flags.isEnabled(Flags.FLAG_VIDEO_RENDERER_DURATION_TO_PROGRESS))
    }

    @Test
    fun frameProcessingOffsetReportsAnAverageOnlyWhenFramesWereMeasured() {
        assertEquals(250L, averageVideoFrameProcessingOffsetUs(1_000L, 4))
        assertEquals(-250L, averageVideoFrameProcessingOffsetUs(-1_000L, 4))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, 0))
        assertEquals(null, averageVideoFrameProcessingOffsetUs(1_000L, -1))
    }
}
