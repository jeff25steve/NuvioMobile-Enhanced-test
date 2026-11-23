package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeReadAheadSizingTest {
    @Test
    fun firstBlockDoesNotExceedConfiguredWindow() {
        assertEquals(4 * 1024 * 1024, readAheadBlockSizeBytes(4L * 1024 * 1024, 0))
    }

    @Test
    fun subsequentBlockUsesOnlyRemainingWindow() {
        assertEquals(4 * 1024 * 1024, readAheadBlockSizeBytes(20L * 1024 * 1024, 16L * 1024 * 1024))
    }

    @Test
    fun blockSizeIsCappedAtSixteenMiB() {
        assertEquals(16 * 1024 * 1024, readAheadBlockSizeBytes(256L * 1024 * 1024, 0))
    }

    @Test
    fun rejectsRequestsWithoutRemainingCapacity() {
        assertFailsWith<IllegalArgumentException> {
            readAheadBlockSizeBytes(8L * 1024 * 1024, 8L * 1024 * 1024)
        }
    }
    @Test
    fun releasesPartialBlockAfterReaderFinishes() {
        assertEquals(true, shouldReleaseReadAheadBlock(512, 512, 4096, readerFinished = true))
    }

    @Test
    fun retainsPartialBlockWhileReaderCanStillAppend() {
        assertEquals(false, shouldReleaseReadAheadBlock(512, 512, 4096, readerFinished = false))
    }

    @Test
    fun fullBlockCanBeRecycledBeforeReaderFinishes() {
        assertEquals(true, shouldReleaseReadAheadBlock(4096, 4096, 4096, readerFinished = false))
    }

    @Test
    fun fullBlockIsNotRecycledAfterProducerFinishes() {
        assertEquals(false, shouldRecycleReadAheadBlock(4096, 4096, readerFinished = true))
    }

    @Test
    fun fullBlockRemainsReusableWhileProducerIsActive() {
        assertEquals(true, shouldRecycleReadAheadBlock(4096, 4096, readerFinished = false))
    }

}
