package com.nuvio.app.features.trakt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TraktCommentsLookupCancellationTest {

    @Test
    fun `cancellation is rethrown from the best effort lookup`() = runBlocking {
        val cancellation = CancellationException("screen closed")
        var failureCallbackInvoked = false

        val thrown = assertFailsWith<CancellationException> {
            bestEffortTraktCommentsLookup(
                request = { throw cancellation },
                onFailure = { failureCallbackInvoked = true },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(false, failureCallbackInvoked)
    }

    @Test
    fun `ordinary lookup errors remain optional and are reported`() = runBlocking {
        val failure = IllegalStateException("lookup unavailable")
        var reported: Exception? = null

        val result = bestEffortTraktCommentsLookup(
            request = { throw failure },
            onFailure = { reported = it },
        )

        assertEquals(null, result)
        assertSame(failure, reported)
    }

    @Test
    fun `successful lookup result is preserved`() = runBlocking {
        val result = bestEffortTraktCommentsLookup(
            request = { "tt1234567" },
            onFailure = { error("Successful lookup must not report an error") },
        )

        assertEquals("tt1234567", result)
    }
}
