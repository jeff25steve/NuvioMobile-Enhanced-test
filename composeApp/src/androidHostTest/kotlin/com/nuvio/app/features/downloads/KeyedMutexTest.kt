package com.nuvio.app.features.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyedMutexTest {
    @Test
    fun sameKeyIsSerializedAndEntryIsReleasedAfterUse() = runBlocking {
        val keyedMutex = KeyedMutex()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            keyedMutex.withLock("same") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            keyedMutex.withLock("same") { secondEntered.complete(Unit) }
        }

        yield()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertTrue(secondEntered.isCompleted)
        assertEquals(0, keyedMutex.trackedKeyCount)
    }

    @Test
    fun differentKeysCanEnterConcurrently() = runBlocking {
        val keyedMutex = KeyedMutex()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            keyedMutex.withLock("first") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            keyedMutex.withLock("second") { secondEntered.complete(Unit) }
        }

        secondEntered.await()
        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(0, keyedMutex.trackedKeyCount)
    }

    @Test
    fun cancelledWaiterReleasesItsKeyReference() = runBlocking {
        val keyedMutex = KeyedMutex()
        val releaseOwner = CompletableDeferred<Unit>()
        val ownerEntered = CompletableDeferred<Unit>()

        val owner = launch {
            keyedMutex.withLock("same") {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()
        val waiter = launch { keyedMutex.withLock("same") { error("cancelled waiter entered") } }
        yield()
        waiter.cancelAndJoin()

        releaseOwner.complete(Unit)
        owner.join()

        assertEquals(0, keyedMutex.trackedKeyCount)
    }
}
