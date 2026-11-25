package com.nuvio.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CleanupCancellationTest {
    @Test
    fun cancelledCallerRetainsEveryCleanupFailure() = runTest {
        val cancellation = CancellationException("Caller cancelled")
        val first = IllegalStateException("First cleanup failed")
        val second = IllegalStateException("Second cleanup failed")
        val observed = CompletableDeferred<CancellationException>()

        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel(cancellation)
            try {
                ensureCleanupCallerActive(
                    listOf(Result.failure<Unit>(first), Result.failure<Unit>(second)),
                )
            } catch (error: CancellationException) {
                observed.complete(error)
            }
        }

        val thrown = observed.await()
        caller.join()

        assertSame(cancellation, thrown)
        assertTrue(thrown.suppressedExceptions.contains(first))
        assertTrue(thrown.suppressedExceptions.contains(second))
    }

    @Test
    fun activeCallerIsNotInterruptedBySuccessfulCleanupResults() = runTest {
        ensureCleanupCallerActive(listOf(Result.success(Unit), Result.success(Unit)))
    }

    @Test
    fun doesNotSuppressTheCancellationExceptionOntoItself() = runTest {
        val cancellation = CancellationException("Cleanup cancelled")
        val observed = CompletableDeferred<CancellationException>()

        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel(cancellation)
            try {
                ensureCleanupCallerActive(listOf(Result.failure<Unit>(cancellation)))
            } catch (error: CancellationException) {
                observed.complete(error)
            }
        }

        val thrown = observed.await()
        caller.join()

        assertSame(cancellation, thrown)
        assertTrue(cancellation.suppressedExceptions.isEmpty())
    }

    @Test
    fun cleanupCancellationRemainsPrimaryAndRetainsOtherFailures() {
        val cancellation = CancellationException("Cleanup operation cancelled")
        val ordinary = IllegalStateException("Local cleanup failed")

        val failure = cleanupFailure(
            listOf(Result.failure<Unit>(ordinary), Result.failure<Unit>(cancellation)),
        )

        assertSame(cancellation, failure)
        assertTrue(cancellation.suppressedExceptions.contains(ordinary))
    }

    @Test
    fun cleanupFailureRetainsEveryOrdinaryFailure() {
        val first = IllegalStateException("First cleanup failed")
        val second = IllegalStateException("Second cleanup failed")

        val failure = cleanupFailure(
            listOf(Result.failure<Unit>(first), Result.failure<Unit>(second)),
        )

        assertSame(first, failure)
        assertTrue(first.suppressedExceptions.contains(second))
    }
}
