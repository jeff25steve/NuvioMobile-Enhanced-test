package com.nuvio.app.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthSessionOperationGateTest {
    @Test
    fun operationsRegisteredBeforeResetAreReturnedForCancellationAndJoin() = runTest {
        val gate = AuthSessionOperationGate()
        val job = Job()
        val operation = assertNotNull(gate.beginOperation(job))

        val activeOperations = gate.beginReset()

        assertEquals(listOf(job), activeOperations)
        assertFalse(gate.isCurrent(operation))
        assertNull(gate.beginOperation(Job()))
        gate.endReset()
    }

    @Test
    fun concurrentOperationsKeepIndependentOwnershipTokens() = runTest {
        val gate = AuthSessionOperationGate()
        val first = assertNotNull(gate.beginOperation(Job()))
        val second = assertNotNull(gate.beginOperation(Job()))

        assertTrue(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertEquals(2, gate.beginReset().size)
        gate.endReset()
    }

    @Test
    fun synchronousAnonymousSessionWriteIsRejectedDuringReset() = runTest {
        val gate = AuthSessionOperationGate()
        var writes = 0

        assertTrue(gate.runIfOpen { writes += 1 })
        gate.beginReset()

        assertFalse(gate.runIfOpen { writes += 1 })
        assertEquals(1, writes)
        gate.endReset()
    }

    @Test
    fun operationsCanStartAgainAfterResetEnds() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        assertNull(gate.beginOperation(Job()))

        gate.endReset()

        val operation = assertNotNull(gate.beginOperation(Job()))
        assertTrue(gate.isCurrent(operation))
    }

    @Test
    fun finishingAnOldOperationDoesNotRemoveANewerOperation() = runTest {
        val gate = AuthSessionOperationGate()
        val old = assertNotNull(gate.beginOperation(Job()))
        gate.finishOperation(old)
        val current = assertNotNull(gate.beginOperation(Job()))

        gate.finishOperation(old)

        assertTrue(gate.isCurrent(current))
        assertEquals(1, gate.beginReset().size)
        gate.endReset()
    }

    @Test
    fun deviceLinkSessionImportIsCancelledAndJoinedByTheSharedResetGate() = runTest {
        val gate = AuthSessionOperationGate()
        val jobFinished = CompletableDeferred<Unit>()
        val deviceLinkJob = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                jobFinished.complete(Unit)
            }
        }
        runCurrent()
        val operation = assertNotNull(gate.beginOperation(deviceLinkJob))

        val activeOperations = gate.beginReset()
        activeOperations.forEach { it.cancelAndJoin() }

        assertTrue(jobFinished.isCompleted)
        assertFalse(gate.isCurrent(operation))
        assertNull(gate.beginOperation(Job()))
        gate.endReset()
    }

    @Test
    fun overlappingResetsKeepOperationsBlockedUntilBothHaveFinished() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        val secondAcquired = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondReset = launch {
            gate.beginReset()
            try {
                secondAcquired.complete(Unit)
                releaseSecond.await()
            } finally {
                gate.endReset()
            }
        }

        runCurrent()
        assertFalse(secondAcquired.isCompleted)
        assertNull(gate.beginOperation(Job()))

        gate.endReset()
        secondAcquired.await()
        assertNull(gate.beginOperation(Job()))

        releaseSecond.complete(Unit)
        secondReset.join()

        assertNotNull(gate.beginOperation(Job()))
    }

    @Test
    fun cancellingAResetAfterMutexHandoffReopensTheGate() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        val handedOffReset = launch {
            gate.beginReset()
            gate.endReset()
        }
        runCurrent()

        // Unlock hands ownership to the queued waiter, but its continuation has not run yet.
        gate.endReset()
        handedOffReset.cancelAndJoin()

        // The cancelled lock acquisition releases Mutex ownership; the gate must follow it.
        assertNotNull(gate.beginOperation(Job()))
    }

    @Test
    fun cancelledHandoffKeepsGateClosedForTheNextQueuedReset() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        val secondReset = launch {
            gate.beginReset()
            gate.endReset()
        }
        val thirdAcquired = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        val thirdReset = launch {
            gate.beginReset()
            try {
                thirdAcquired.complete(Unit)
                releaseThird.await()
            } finally {
                gate.endReset()
            }
        }
        runCurrent()

        // The first reset hands ownership to the second waiter; cancel it before it resumes.
        gate.endReset()
        secondReset.cancelAndJoin()

        // The third waiter must inherit the reset lease without opening an operation window.
        thirdAcquired.await()
        assertNull(gate.beginOperation(Job()))

        releaseThird.complete(Unit)
        thirdReset.join()
        assertNotNull(gate.beginOperation(Job()))
    }

    @Test
    fun cancellingAQueuedResetDoesNotReleaseTheCurrentReset() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        val queuedReset = launch {
            gate.beginReset()
            gate.endReset()
        }
        runCurrent()

        queuedReset.cancelAndJoin()
        assertNull(gate.beginOperation(Job()))

        gate.endReset()
        assertNotNull(gate.beginOperation(Job()))
    }

    @Test
    fun cancellingOneQueuedResetDoesNotReleaseAnotherQueuedResetReservation() = runTest {
        val gate = AuthSessionOperationGate()
        gate.beginReset()

        val cancelledWaiter = launch {
            gate.beginReset()
            gate.endReset()
        }
        val nextAcquired = CompletableDeferred<Unit>()
        val releaseNext = CompletableDeferred<Unit>()
        val nextWaiter = launch {
            gate.beginReset()
            try {
                nextAcquired.complete(Unit)
                releaseNext.await()
            } finally {
                gate.endReset()
            }
        }
        runCurrent()

        cancelledWaiter.cancelAndJoin()
        assertNull(gate.beginOperation(Job()))

        gate.endReset()
        nextAcquired.await()
        assertNull(gate.beginOperation(Job()))

        releaseNext.complete(Unit)
        nextWaiter.join()
        assertNotNull(gate.beginOperation(Job()))
    }

}
