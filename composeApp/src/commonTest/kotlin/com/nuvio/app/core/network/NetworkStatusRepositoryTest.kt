package com.nuvio.app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkStatusRepositoryTest {
    @Test
    fun ordinaryTransportFailureMeansEndpointIsNotReachable() = runTest {
        val reachable = probeHttpReachability(timeoutMillis = 1_000L) {
            throw IllegalStateException("connection failed")
        }

        assertFalse(reachable)
    }

    @Test
    fun anyValidHttpResponseStillCountsAsReachable() = runTest {
        val reachable = probeHttpReachability(timeoutMillis = 1_000L) { 503 }

        assertTrue(reachable)
    }

    @Test
    fun invalidHttpStatusCodesAreNotReachable() = runTest {
        assertFalse(probeHttpReachability(timeoutMillis = 1_000L) { 99 })
        assertFalse(probeHttpReachability(timeoutMillis = 1_000L) { 600 })
    }

    @Test
    fun probeOwnTimeoutMeansEndpointIsNotReachable() = runTest {
        val reachable = probeHttpReachability(timeoutMillis = 1L) {
            awaitCancellation<Int>()
        }

        assertFalse(reachable)
    }

    @Test
    fun callerCancellationPropagatesFromAnInFlightProbe() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancellation = CancellationException("caller no longer needs the result")
        val request = async {
            probeHttpReachability(timeoutMillis = 1_000L) {
                started.complete(Unit)
                awaitCancellation<Int>()
            }
        }

        started.await()
        request.cancel(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            request.await()
        }
        assertEquals(cancellation.message, thrown.message)
    }
}
