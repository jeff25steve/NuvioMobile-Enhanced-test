package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerationBoundedByteLruCacheTest {
    @Test
    fun clearRejectsAWriteFromALoadThatStartedBeforeThePurge() {
        val cache = GenerationBoundedByteLruCache(maxBytes = 8L)
        // This snapshot represents a request that may wait for a load permit.
        val inFlightGeneration = cache.generationSnapshot()

        cache.clear()

        assertFalse(
            cache.putIfCurrent(
                key = "remote-image",
                bytes = byteArrayOf(1, 2, 3),
                expectedGeneration = inFlightGeneration,
            ),
        )
        assertNull(cache.lookup("remote-image"))

        val afterPurgeGeneration = cache.generationSnapshot()
        assertTrue(
            cache.putIfCurrent(
                key = "remote-image",
                bytes = byteArrayOf(4, 5),
                expectedGeneration = afterPurgeGeneration,
            ),
        )
        assertContentEquals(byteArrayOf(4, 5), cache.lookup("remote-image"))
    }

    @Test
    fun lookupRefreshesLruOrderAndEvictionRespectsTheByteBudget() {
        val cache = GenerationBoundedByteLruCache(maxBytes = 5L)
        val generation = cache.generationSnapshot()

        assertTrue(cache.putIfCurrent("first", byteArrayOf(1, 2), generation))
        assertTrue(cache.putIfCurrent("second", byteArrayOf(3, 4, 5), generation))
        assertContentEquals(byteArrayOf(1, 2), cache.lookup("first"))

        assertTrue(cache.putIfCurrent("third", byteArrayOf(6, 7, 8), generation))

        assertNull(cache.lookup("second"))
        assertContentEquals(byteArrayOf(1, 2), cache.lookup("first"))
        assertContentEquals(byteArrayOf(6, 7, 8), cache.lookup("third"))
    }

    @Test
    fun replacingAnEntrySubtractsItsPreviousByteCost() {
        val cache = GenerationBoundedByteLruCache(maxBytes = 5L)
        val generation = cache.generationSnapshot()

        assertTrue(cache.putIfCurrent("first", byteArrayOf(1, 2, 3, 4), generation))
        assertTrue(cache.putIfCurrent("second", byteArrayOf(5), generation))
        assertTrue(cache.putIfCurrent("first", byteArrayOf(6, 7), generation))
        assertTrue(cache.putIfCurrent("third", byteArrayOf(8, 9), generation))

        assertContentEquals(byteArrayOf(5), cache.lookup("second"))
        assertContentEquals(byteArrayOf(6, 7), cache.lookup("first"))
        assertContentEquals(byteArrayOf(8, 9), cache.lookup("third"))
    }
}
