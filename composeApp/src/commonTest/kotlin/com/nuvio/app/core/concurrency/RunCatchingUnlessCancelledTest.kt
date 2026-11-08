package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
}
