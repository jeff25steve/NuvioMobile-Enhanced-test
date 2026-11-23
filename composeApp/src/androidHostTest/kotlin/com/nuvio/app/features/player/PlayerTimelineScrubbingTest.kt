package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
