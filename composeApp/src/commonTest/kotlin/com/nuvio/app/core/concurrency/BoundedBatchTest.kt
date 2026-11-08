package com.nuvio.app.core.concurrency

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedBatchTest {

    @Test
    fun `limits active work and preserves source order`() = runTest {
        val lock = SynchronizedObject()
        var active = 0
        var maximumActive = 0
        val input = (0 until 11).toList()

        val result = mapInBatchesConcurrently(input, batchSize = 3) { value ->
            synchronized(lock) {
                active += 1
                maximumActive = maxOf(maximumActive, active)
            }
            try {
                delay((input.size - value).toLong())
                value * 2
            } finally {
                synchronized(lock) {
                    active -= 1
                }
            }
        }

        assertEquals(listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20), result)
        assertEquals(3, maximumActive)
        assertEquals(0, active)
    }

    @Test
    fun `empty input returns an empty result`() = runTest {
        var transformCalled = false

        val result = mapInBatchesConcurrently(emptyList<Int>(), batchSize = 3) {
            transformCalled = true
            it
        }

        assertEquals(emptyList(), result)
        assertEquals(false, transformCalled)
    }

    @Test
    fun `cancellation prevents later batches from starting`() = runTest {
        var started = 0
        val firstBatchStarted = CompletableDeferred<Unit>()
        val job = launch {
            mapInBatchesConcurrently((0 until 5).toList(), batchSize = 2) {
                started += 1
                if (started == 2) firstBatchStarted.complete(Unit)
                awaitCancellation()
            }
        }

        firstBatchStarted.await()
        assertEquals(2, started)

        job.cancelAndJoin()
        assertEquals(2, started)
    }
}
