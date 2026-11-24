@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class AdjustableLruCacheEvictorTest {

    @Test
    fun loweringLimitImmediatelyEvictsLeastRecentlyUsedSpans() {
        val evictor = AdjustableLruCacheEvictor(initialMaxBytes = 200L)
        val cache = FakeCache(evictor)
        cache.add(span("old", length = 80L, touchedAt = 1L))
        cache.add(span("new", length = 70L, touchedAt = 2L))

        evictor.setMaxBytes(cache.cache, 100L)

        assertEquals(70L, cache.cacheSpace)
        assertEquals(setOf("new"), cache.keys)
    }

    @Test
    fun increasingLimitAllowsSubsequentWritesWithinNewLimit() {
        val evictor = AdjustableLruCacheEvictor(initialMaxBytes = 100L)
        val cache = FakeCache(evictor)
        cache.add(span("first", length = 80L, touchedAt = 1L))

        evictor.setMaxBytes(cache.cache, 200L)
        cache.add(span("second", length = 100L, touchedAt = 2L))

        assertEquals(180L, cache.cacheSpace)
        assertEquals(setOf("first", "second"), cache.keys)
    }

    @Test
    fun knownWriteReservationEvictsEnoughLeastRecentlyUsedBytesBeforeWriting() {
        val evictor = AdjustableLruCacheEvictor(initialMaxBytes = 100L)
        val cache = FakeCache(evictor)
        cache.add(span("old", length = 60L, touchedAt = 1L))
        cache.add(span("new", length = 30L, touchedAt = 2L))

        evictor.onStartFile(cache.cache, "incoming", 0L, 40L)

        assertEquals(30L, cache.cacheSpace)
        assertEquals(setOf("new"), cache.keys)
    }

    @Test
    fun unknownWriteLengthDoesNotEvictBeforeTheWriteSizeIsKnown() {
        val evictor = AdjustableLruCacheEvictor(initialMaxBytes = 100L)
        val cache = FakeCache(evictor)
        cache.add(span("first", length = 60L, touchedAt = 1L))
        cache.add(span("second", length = 30L, touchedAt = 2L))

        evictor.onStartFile(cache.cache, "incoming", 0L, C.LENGTH_UNSET.toLong())

        assertEquals(90L, cache.cacheSpace)
        assertEquals(setOf("first", "second"), cache.keys)
    }

    @Test
    fun autoSizeRecomputesFromCurrentUsableSpaceAndKeepsConfiguredBounds() {
        val oneGiB = 1024L * 1024L * 1024L

        assertEquals(512L * 1024L * 1024L, calculateVodDiskCacheMaxBytes(2L * oneGiB, true, 2048))
        assertEquals(256L * 1024L * 1024L, calculateVodDiskCacheMaxBytes(512L * 1024L * 1024L, true, 2048))
        assertEquals(10_240L * 1024L * 1024L, calculateVodDiskCacheMaxBytes(100L * oneGiB, true, 2048))
        assertEquals(3L * 1024L * 1024L, calculateVodDiskCacheMaxBytes(100L * oneGiB, false, 3))
    }

    @Test
    fun touchingSpanUpdatesLruOrderBeforeNextWrite() {
        val evictor = AdjustableLruCacheEvictor(initialMaxBytes = 100L)
        val cache = FakeCache(evictor)
        val first = span("first", length = 50L, touchedAt = 1L)
        val second = span("second", length = 50L, touchedAt = 2L)
        cache.add(first)
        cache.add(second)

        cache.touch(first, span("first", length = 50L, touchedAt = 3L))
        cache.add(span("third", length = 50L, touchedAt = 4L))

        assertEquals(100L, cache.cacheSpace)
        assertEquals(setOf("first", "third"), cache.keys)
    }

    private fun span(key: String, length: Long, touchedAt: Long) =
        CacheSpan(key, 0L, length, touchedAt, File("/$key.cache"))

    private class FakeCache(private val evictor: AdjustableLruCacheEvictor) {
        private val spans = mutableListOf<CacheSpan>()
        var cacheSpace = 0L
            private set

        val cache: Cache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "removeSpan" -> {
                    val span = args!![0] as CacheSpan
                    if (spans.remove(span)) {
                        cacheSpace -= span.length
                        evictor.onSpanRemoved(proxy as Cache, span)
                    }
                    null
                }
                "getCacheSpace" -> cacheSpace
                "toString" -> "FakeCache"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected Cache method: ${method.name}")
            }
        } as Cache

        val keys: Set<String>
            get() = spans.mapTo(mutableSetOf()) { it.key }

        fun add(span: CacheSpan) {
            spans += span
            cacheSpace += span.length
            evictor.onSpanAdded(cache, span)
        }

        fun touch(oldSpan: CacheSpan, newSpan: CacheSpan) {
            check(spans.remove(oldSpan))
            spans += newSpan
            evictor.onSpanTouched(cache, oldSpan, newSpan)
        }
    }
}
