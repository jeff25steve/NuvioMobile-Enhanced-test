package com.nuvio.app.core.auth

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthSessionOperationGateTest {
    @Test
    fun operationsRegisteredBeforeSwitchAreReturnedForCancellationAndJoin() {
        val gate = AuthSessionOperationGate()
        val job = Job()
        val operation = assertNotNull(gate.beginOperation(job))

        val activeOperations = gate.beginServerSwitch()

        assertEquals(listOf(job), activeOperations)
        assertFalse(gate.isCurrent(operation))
        assertNull(gate.beginOperation(Job()))
    }

    @Test
    fun concurrentOperationsKeepIndependentOwnershipTokens() {
        val gate = AuthSessionOperationGate()
        val first = assertNotNull(gate.beginOperation(Job()))
        val second = assertNotNull(gate.beginOperation(Job()))

        assertTrue(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertEquals(2, gate.beginServerSwitch().size)
    }

    @Test
    fun operationsCanStartAgainAfterSwitchEnds() {
        val gate = AuthSessionOperationGate()
        gate.beginServerSwitch()

        assertNull(gate.beginOperation(Job()))

        gate.endServerSwitch()

        val operation = assertNotNull(gate.beginOperation(Job()))
        assertTrue(gate.isCurrent(operation))
    }

    @Test
    fun finishingAnOldOperationDoesNotRemoveANewerOperation() {
        val gate = AuthSessionOperationGate()
        val old = assertNotNull(gate.beginOperation(Job()))
        gate.finishOperation(old)
        val current = assertNotNull(gate.beginOperation(Job()))

        gate.finishOperation(old)

        assertTrue(gate.isCurrent(current))
        assertEquals(1, gate.beginServerSwitch().size)
    }
}
