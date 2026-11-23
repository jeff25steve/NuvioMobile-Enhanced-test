package com.nuvio.app.features.player.swdecoder

import kotlin.test.Test
import kotlin.test.assertEquals

class SoftwareVideoFrameTimestampTest {
    @Test
    fun prefersDecodedPresentationTimestamp() {
        assertEquals(1_250_000L, resolveSoftwareFrameTimestampUs(1_250_000L, 1_200_000L, 1_300_000L))
    }

    @Test
    fun usesBestEffortTimestampWhenDecodedPtsIsMissing() {
        assertEquals(1_200_000L, resolveSoftwareFrameTimestampUs(Long.MIN_VALUE, 1_200_000L, 1_300_000L))
    }

    @Test
    fun fallsBackToPacketTimestampWhenBothFrameTimestampsAreMissing() {
        assertEquals(1_300_000L, resolveSoftwareFrameTimestampUs(Long.MIN_VALUE, Long.MIN_VALUE, 1_300_000L))
    }
}
