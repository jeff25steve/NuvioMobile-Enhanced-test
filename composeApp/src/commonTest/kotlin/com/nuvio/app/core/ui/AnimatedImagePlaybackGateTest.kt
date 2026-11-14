package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimatedImagePlaybackGateTest {
    @Test
    fun nestedPlaybackSurfacesKeepAnimationsSuspendedUntilLastSurfaceLeaves() {
        val gate = AnimatedImagePlaybackGate()

        assertTrue(gate.suspendPlayback())
        assertTrue(gate.isSuspended)
        assertEquals(false, gate.suspendPlayback())
        assertEquals(false, gate.resumePlayback())
        assertTrue(gate.isSuspended)
        assertTrue(gate.resumePlayback())
        assertEquals(false, gate.isSuspended)
        assertTrue(gate.canFinishResumeCooldown())
    }

    @Test
    fun unbalancedResumeDoesNotChangePlaybackGate() {
        val gate = AnimatedImagePlaybackGate()

        assertEquals(false, gate.resumePlayback())
        assertEquals(false, gate.isSuspended)
        assertTrue(gate.canFinishResumeCooldown())
    }

    @Test
    fun aNewPlaybackInvalidatesAnEarlierResumeCooldown() {
        val gate = AnimatedImagePlaybackGate()

        assertTrue(gate.suspendPlayback())
        assertTrue(gate.resumePlayback())
        assertTrue(gate.canFinishResumeCooldown())

        assertTrue(gate.suspendPlayback())
        assertEquals(false, gate.canFinishResumeCooldown())
        assertTrue(gate.resumePlayback())
        assertTrue(gate.canFinishResumeCooldown())
    }
}
