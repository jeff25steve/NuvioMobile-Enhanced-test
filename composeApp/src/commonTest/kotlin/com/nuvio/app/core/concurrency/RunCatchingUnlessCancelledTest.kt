package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunCatchingUnlessCancelledTest {

    @Test
    fun `cancellation is rethrown unchanged`() = runBlocking {
        val cancellation = CancellationException("request no longer needed")

        val thrown = assertFailsWith<CancellationException> {
            runCatchingUnlessCancelled<String> {
                throw cancellation
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `ordinary failures remain available as result failures`() = runBlocking {
        val failure = IllegalStateException("provider unavailable")

        val result = runCatchingUnlessCancelled<String> {
            throw failure
        }

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `successful values are preserved`() = runBlocking {
        val result = runCatchingUnlessCancelled {
            "metadata"
        }

        assertEquals("metadata", result.getOrNull())
    }

    @Test
    fun `its own timeout remains a recoverable null result`() = runTest {
        val result = runCatchingUnlessCancelled {
            withTimeoutOrNull(1L) {
                awaitCancellation()
            }
        }

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `caller cancellation is not converted to the timeout fallback`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancellation = CancellationException("caller no longer needs the result")
        val request = async {
            withTimeoutOrNull(1_000L) {
                runCatchingUnlessCancelled<String> {
                    started.complete(Unit)
                    awaitCancellation()
                }
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
