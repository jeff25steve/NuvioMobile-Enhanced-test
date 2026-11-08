package com.nuvio.app.features.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudLibrarySafetyTest {

    @Test
    fun `provider list wrapper rethrows cancellation instead of returning a failure result`() = runBlocking {
        val cancelled = CancellationException("cloud library request cancelled")

        val thrown = assertFailsWith<CancellationException> {
            cloudLibraryListResult<String> { throw cancelled }
        }

        assertEquals(cancelled, thrown)
    }

    @Test
    fun `provider list wrapper preserves recoverable failures as result failures`() = runBlocking {
        val failure = IllegalStateException("provider unavailable")

        val result = cloudLibraryListResult<String> { throw failure }

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }

    @Test
    fun `provider list wrapper returns successful values unchanged`() = runBlocking {
        val result = cloudLibraryListResult { listOf("first", "second") }

        assertTrue(result.isSuccess)
        assertEquals(listOf("first", "second"), result.getOrNull())
    }
}
