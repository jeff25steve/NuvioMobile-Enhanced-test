package com.nuvio.app.features.player.seekpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekPreviewCacheTest {
    @Test
    fun cacheEvictsOldestEntriesWhenByteBudgetIsExceeded() {
        val cache = linkedMapOf(1L to 10L, 2L to 20L, 3L to 30L)

        val retained = trimSeekPreviewCache(
            cache = cache,
            maxEntries = 100,
            maxBytes = 40L,
            sizeInBytes = { it },
        )

        assertEquals(listOf(3L), cache.keys.toList())
        assertEquals(30L, retained)
    }

    @Test
    fun cacheEvictsOldestEntriesWhenEntryLimitIsExceeded() {
        val cache = linkedMapOf(1L to 8L, 2L to 8L, 3L to 8L)

        val retained = trimSeekPreviewCache(
            cache = cache,
            maxEntries = 2,
            maxBytes = 1_000L,
            sizeInBytes = { it },
        )

        assertEquals(listOf(2L, 3L), cache.keys.toList())
        assertEquals(16L, retained)
    }

    @Test
    fun cacheDropsAnOversizedNewestFrameInsteadOfBreakingTheBudget() {
        val cache = linkedMapOf(1L to 100L)

        val retained = trimSeekPreviewCache(
            cache = cache,
            maxEntries = 100,
            maxBytes = 64L,
            sizeInBytes = { it },
        )

        assertTrue(cache.isEmpty())
        assertEquals(0L, retained)
    }

    @Test
    fun cacheTreatsNegativeSizesAndLimitsAsZero() {
        val cache = linkedMapOf(1L to -5L, 2L to 10L)

        val retained = trimSeekPreviewCache(
            cache = cache,
            maxEntries = -1,
            maxBytes = -1L,
            sizeInBytes = { it },
        )

        assertTrue(cache.isEmpty())
        assertEquals(0L, retained)
    }

    @Test
    fun previewDistanceRejectsDistantAndInvalidFrames() {
        assertTrue(isSeekPreviewFrameWithinDistance(2_000L, 3_000L, 1_000L))
        assertFalse(isSeekPreviewFrameWithinDistance(1_000L, 3_001L, 2_000L))
        assertFalse(isSeekPreviewFrameWithinDistance(-1L, 1_000L, 2_000L))
        assertFalse(isSeekPreviewFrameWithinDistance(1_000L, 1_000L, -1L))
    }

    @Test
    fun previewDistanceHandlesLongTimelinePositionsWithoutAbsoluteValueOverflow() {
        assertTrue(isSeekPreviewFrameWithinDistance(Long.MAX_VALUE, Long.MAX_VALUE - 1L, 1L))
        assertFalse(isSeekPreviewFrameWithinDistance(Long.MAX_VALUE, 0L, 2_000L))
    }
}
