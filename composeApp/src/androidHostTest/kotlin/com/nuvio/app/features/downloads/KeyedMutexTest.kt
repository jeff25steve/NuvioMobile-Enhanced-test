package com.nuvio.app.features.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyedMutexTest {
    @Test
    fun differentKeysCanEnterTheirCriticalSectionsConcurrently(): Unit = runBlocking {
        val locks = KeyedMutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            locks.withLock("first") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }

        try {
            firstEntered.await()
            val second = async(Dispatchers.Default) {
                locks.withLock("second") { secondEntered.complete(Unit) }
            }

            withTimeout(2_000) { secondEntered.await() }
            second.await()
            assertEquals(1, locks.activeKeyCount())
        } finally {
            releaseFirst.complete(Unit)
            first.await()
        }

        assertEquals(0, locks.activeKeyCount())
    }

    @Test
    fun sameKeyWaitersShareOneMutexAndIdleEntryIsRemoved(): Unit = runBlocking {
        val locks = KeyedMutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            locks.withLock("same") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }

        try {
            firstEntered.await()
            val second = async(Dispatchers.Default) {
                locks.withLock("same") { secondEntered.complete(Unit) }
            }
            withTimeout(2_000) {
                while (locks.activeUsers("same") < 2) delay(1)
            }
            assertFalse(secondEntered.isCompleted)
            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertTrue(secondEntered.isCompleted)
        } finally {
            releaseFirst.complete(Unit)
            first.await()
        }

        assertEquals(0, locks.activeKeyCount())
    }

    @Test
    fun cancellingAWaiterDoesNotRemoveTheHolderEntry(): Unit = runBlocking {
        val locks = KeyedMutex()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val nextWaiterEntered = CompletableDeferred<Unit>()

        val holder = async(Dispatchers.Default) {
            locks.withLock("same") {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }

        try {
            holderEntered.await()
            val cancelledWaiter = async(Dispatchers.Default) {
                locks.withLock("same") { error("Cancelled waiter must not enter") }
            }
            withTimeout(2_000) {
                while (locks.activeUsers("same") < 2) delay(1)
            }

            cancelledWaiter.cancelAndJoin()
            assertEquals(1, locks.activeUsers("same"))

            val nextWaiter = async(Dispatchers.Default) {
                locks.withLock("same") { nextWaiterEntered.complete(Unit) }
            }
            withTimeout(2_000) {
                while (locks.activeUsers("same") < 2) delay(1)
            }
            assertFalse(nextWaiterEntered.isCompleted)

            releaseHolder.complete(Unit)
            holder.await()
            nextWaiter.await()
            assertTrue(nextWaiterEntered.isCompleted)
        } finally {
            releaseHolder.complete(Unit)
            holder.await()
        }

        assertEquals(0, locks.activeKeyCount())
    }
}
