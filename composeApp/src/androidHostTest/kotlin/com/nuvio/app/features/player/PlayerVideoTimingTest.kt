package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerVideoTimingTest {
    @Test
    fun preservesFractionalFilmFrameRates() {
        assertEquals(23.976f, normalizedVideoFrameRate(23.976f))
        assertEquals(29.97f, normalizedVideoFrameRate(29.97f))
    }

    @Test
    fun rejectsUnknownAndInvalidFrameRates() {
        assertNull(normalizedVideoFrameRate(-1f))
        assertNull(normalizedVideoFrameRate(0f))
        assertNull(normalizedVideoFrameRate(Float.NaN))
        assertNull(normalizedVideoFrameRate(Float.POSITIVE_INFINITY))
        assertNull(normalizedVideoFrameRate(Float.NEGATIVE_INFINITY))
    }
}
