package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerTimelineScrubbingTest {
    @Test
    fun scrubbingModeIsEnabledOnlyForAnEnabledDragWithKnownDuration() {
        assertTrue(shouldEnablePlayerScrubbing(isDragged = true, enabled = true, durationMs = 60_000L))
        assertFalse(shouldEnablePlayerScrubbing(isDragged = false, enabled = true, durationMs = 60_000L))
        assertFalse(shouldEnablePlayerScrubbing(isDragged = true, enabled = false, durationMs = 60_000L))
        assertFalse(shouldEnablePlayerScrubbing(isDragged = true, enabled = true, durationMs = 0L))
        assertFalse(shouldEnablePlayerScrubbing(isDragged = true, enabled = true, durationMs = -1L))
    }

    @Test
    fun fractionalSeekToleranceTracksTouchWidthAndDensity() {
        assertEquals(0.005, fractionalSeekToleranceForTrack(widthPx = 400, density = 1f)!!)
        assertEquals(0.005, fractionalSeekToleranceForTrack(widthPx = 1_200, density = 3f)!!)
        assertNull(fractionalSeekToleranceForTrack(widthPx = 0, density = 1f))
        assertNull(fractionalSeekToleranceForTrack(widthPx = 400, density = 0f))
    }
}
