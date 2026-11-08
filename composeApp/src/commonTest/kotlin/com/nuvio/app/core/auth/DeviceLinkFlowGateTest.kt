package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceLinkFlowGateTest {
    @Test
    fun onlyOneDeviceLinkAttemptCanBeActive() {
        val gate = DeviceLinkFlowGate()

        val first = assertNotNull(gate.beginAttempt())

        assertNull(gate.beginAttempt())
        assertTrue(gate.isCurrent(first))
    }

    @Test
    fun cancellingAnAttemptInvalidatesItsPendingWork() {
        val gate = DeviceLinkFlowGate()
        val attempt = assertNotNull(gate.beginAttempt())

        gate.cancelAttempt()

        assertFalse(gate.isCurrent(attempt))
        assertNotNull(gate.beginAttempt())
    }

    @Test
    fun serverSwitchInvalidatesAndBlocksDeviceLinkAttemptsUntilFinished() {
        val gate = DeviceLinkFlowGate()
        val attempt = assertNotNull(gate.beginAttempt())

        gate.beginServerSwitch()

        assertFalse(gate.isCurrent(attempt))
        assertNull(gate.beginAttempt())

        gate.endServerSwitch()

        val next = assertNotNull(gate.beginAttempt())
        assertTrue(gate.isCurrent(next))
        assertFalse(gate.isCurrent(attempt))
    }

    @Test
    fun finishingOnlyReleasesTheMatchingAttempt() {
        val gate = DeviceLinkFlowGate()
        val first = assertNotNull(gate.beginAttempt())

        gate.finishAttempt(first)

        val second = assertNotNull(gate.beginAttempt())
        gate.finishAttempt(first)

        assertTrue(gate.isCurrent(second))
        assertNull(gate.beginAttempt())
    }
}
