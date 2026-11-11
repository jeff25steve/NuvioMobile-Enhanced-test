package com.nuvio.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkRefreshGateTest {
    @Test
    fun concurrentCallersStartOnlyOneProbe() = runTest {
        val gate = NetworkRefreshGate()
        val start = CompletableDeferred<Unit>()
        val requests = List(64) {
            async(Dispatchers.Default) {
                start.await()
                gate.request(force = false, confirmFailures = false)
            }
        }

        start.complete(Unit)

        assertEquals(1, requests.awaitAll().count { it })
        assertNull(gate.finishProbe())
    }

    @Test
    fun concurrentEnsureStartedCallsScheduleOnlyOneInitialProbe() = runTest {
        val gate = NetworkRefreshGate()
        val start = CompletableDeferred<Unit>()
        val requests = List(64) {
            async(Dispatchers.Default) {
                start.await()
                gate.ensureStarted()
            }
        }

        start.complete(Unit)

        assertEquals(1, requests.awaitAll().count { it })
        assertNull(gate.finishProbe())
    }

    @Test
    fun forcedRefreshRequestsCoalesceAndMergeFailureConfirmation() {
        val gate = NetworkRefreshGate()

        assertTrue(gate.request(force = false, confirmFailures = false))
        assertFalse(gate.request(force = true, confirmFailures = false))
        assertFalse(gate.request(force = true, confirmFailures = true))

        assertEquals(true, gate.finishProbe())
        assertNull(gate.finishProbe())
        assertTrue(gate.request(force = false, confirmFailures = false))
    }

    @Test
    fun abortedProbeAllowsEnsureStartedToScheduleAReplacement() {
        val gate = NetworkRefreshGate()

        assertTrue(gate.request(force = false, confirmFailures = false))
        assertFalse(gate.request(force = true, confirmFailures = true))
        gate.abort()

        assertTrue(gate.ensureStarted())
        assertNull(gate.finishProbe())
    }
}
