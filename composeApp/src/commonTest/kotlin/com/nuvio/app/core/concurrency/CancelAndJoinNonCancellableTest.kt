package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelAndJoinNonCancellableTest {
    @Test
    fun cancelAndJoinWaitsForTheCancelledJobsFinallyBlock() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()
        val operation = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    allowCleanupToFinish.await()
                }
            }
        }

        val waiter = launch {
            cancelAndJoinNonCancellable(operation)
        }

        cleanupStarted.await()
        assertFalse(waiter.isCompleted)

        allowCleanupToFinish.complete(Unit)
        waiter.join()

        assertTrue(operation.isCompleted)
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun cancelledCallerStillWaitsForTheCancelledJobsCleanup() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()
        val operation = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    allowCleanupToFinish.await()
                }
            }
        }

        val waiter = launch {
            cancelAndJoinNonCancellable(operation)
        }

        cleanupStarted.await()
        waiter.cancel()
        assertFalse(waiter.isCompleted)

        allowCleanupToFinish.complete(Unit)
        waiter.join()

        assertTrue(operation.isCompleted)
    }
}
