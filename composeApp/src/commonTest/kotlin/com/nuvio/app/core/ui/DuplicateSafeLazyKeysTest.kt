package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DuplicateSafeLazyKeysTest {
    @Test
    fun `duplicate lazy keys receive stable occurrence suffixes`() {
        val result = listOf("duplicate", "other", "duplicate")
            .withDuplicateSafeLazyKeys { value -> value }

        assertEquals(listOf("duplicate#0", "other", "duplicate#1"), result.map { it.lazyKey })
    }

    @Test
    fun `key cache reuses one projection for the same source instance`() {
        val source = listOf("duplicate", "other", "duplicate")
        val cache = DuplicateSafeLazyKeysCache<String> { it }
        var keyCalls = 0

        val first = cache.getOrCreate(source) { value ->
            keyCalls += 1
            value
        }
        val second = cache.getOrCreate(source) { value ->
            keyCalls += 1
            value
        }

        assertSame(first, second)
        assertEquals(3, keyCalls)
    }

    @Test
    fun `key cache rebuilds projection when source instance changes`() {
        val source = listOf("duplicate", "other", "duplicate")
        val replacement = source.map { it }
        val cache = DuplicateSafeLazyKeysCache<String> { it }

        val first = cache.getOrCreate(source)
        val second = cache.getOrCreate(replacement)

        assertNotSame(first, second)
        assertEquals(first.map { it.lazyKey }, second.map { it.lazyKey })
    }
}
