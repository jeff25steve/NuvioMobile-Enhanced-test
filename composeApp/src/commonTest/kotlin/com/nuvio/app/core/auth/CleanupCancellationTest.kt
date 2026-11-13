package com.nuvio.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CleanupCancellationTest {
    @Test
    fun cancelledCallerRetainsEveryCleanupFailure() = runTest {
        val first = IllegalStateException("First cleanup failed")
        val second = IllegalStateException("Second cleanup failed")
        var thrown: CancellationException? = null

        try {
            ensureCleanupCallerActive(
                listOf(Result.failure(first), Result.failure(second)),
            )
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown != null)
        assertTrue(thrown!!.suppressedExceptions.contains(first))
        assertTrue(thrown!!.suppressedExceptions.contains(second))
    }

    @Test
    fun activeCallerIsNotInterruptedBySuccessfulCleanupResults() = runTest {
        ensureCleanupCallerActive(listOf(Result.success(Unit), Result.success(Unit)))
    }

    @Test
    fun doesNotSuppressTheCancellationExceptionOntoItself() = runTest {
        val cancellation = CancellationException("Cleanup cancelled")
        var thrown: CancellationException? = null

        try {
            ensureCleanupCallerActive(listOf(Result.failure(cancellation)))
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(cancellation, thrown)
        assertTrue(cancellation.suppressedExceptions.isEmpty())
    }

    @Test
    fun cleanupCancellationRemainsPrimaryAndRetainsOtherFailures() {
        val cancellation = CancellationException("Cleanup operation cancelled")
        val ordinary = IllegalStateException("Local cleanup failed")

        val failure = cleanupFailure(
            listOf(Result.failure(ordinary), Result.failure(cancellation)),
        )

        assertSame(cancellation, failure)
        assertTrue(cancellation.suppressedExceptions.contains(ordinary))
    }

    @Test
    fun cleanupFailureRetainsEveryOrdinaryFailure() {
        val first = IllegalStateException("First cleanup failed")
        val second = IllegalStateException("Second cleanup failed")

        val failure = cleanupFailure(
            listOf(Result.failure(first), Result.failure(second)),
        )

        assertSame(first, failure)
        assertTrue(first.suppressedExceptions.contains(second))
    }
}
