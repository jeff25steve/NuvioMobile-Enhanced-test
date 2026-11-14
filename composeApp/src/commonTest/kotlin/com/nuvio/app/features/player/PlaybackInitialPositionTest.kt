package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackInitialPositionTest {
    @Test
    fun convertsPositiveMillisecondsToLocaleIndependentMpvSeconds() {
        assertEquals("0.001", mpvStartPositionSeconds(1L))
        assertEquals("12.345", mpvStartPositionSeconds(12_345L))
        assertEquals("3600.007", mpvStartPositionSeconds(3_600_007L))
    }

    @Test
    fun omitsZeroAndNegativeStartPositions() {
        assertNull(mpvStartPositionSeconds(0L))
        assertNull(mpvStartPositionSeconds(-1L))
    }
}
