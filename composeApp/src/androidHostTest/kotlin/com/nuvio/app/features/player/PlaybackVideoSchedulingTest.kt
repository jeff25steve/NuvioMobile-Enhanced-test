package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackVideoSchedulingTest {
    @Test
    fun mediaCodecProgressSchedulingIsEnabledWhenAsynchronousQueueingIsDefault() {
        assertFalse(shouldEnableMediaCodecVideoRendererDurationToProgressUs(30))
        assertTrue(shouldEnableMediaCodecVideoRendererDurationToProgressUs(31))
        assertTrue(shouldEnableMediaCodecVideoRendererDurationToProgressUs(35))
    }
}
