package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerEngineStartPositionTest {
    @Test
    fun zeroAndUnsetPositionsUseTheDefaultMediaStart() {
        assertFalse(shouldUseExplicitStartPosition(null))
        assertFalse(shouldUseExplicitStartPosition(0L))
        assertFalse(shouldUseExplicitStartPosition(-1L))
    }

    @Test
    fun positiveResumePositionsRemainExplicit() {
        assertTrue(shouldUseExplicitStartPosition(1L))
        assertTrue(shouldUseExplicitStartPosition(90_000L))
    }
}
