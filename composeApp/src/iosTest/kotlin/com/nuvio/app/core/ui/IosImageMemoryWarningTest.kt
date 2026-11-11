package com.nuvio.app.core.ui

import coil3.Canvas
import coil3.Image
import coil3.memory.MemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosImageMemoryWarningTest {
    @Test
    fun memoryWarningClearsCachedImages() {
        val cache = MemoryCache.Builder()
            .maxSizeBytes(1024L)
            .build()
        val key = MemoryCache.Key("poster")
        cache[key] = MemoryCache.Value(TestImage())

        assertEquals(4L, cache.size)

        clearIosImageMemoryCacheOnWarning(cache)

        assertEquals(0L, cache.size)
        assertNull(cache[key])
    }

    private class TestImage : Image {
        override val size: Long = 4L
        override val width: Int = 1
        override val height: Int = 1
        override val shareable: Boolean = true

        override fun draw(canvas: Canvas) = Unit
    }
}
