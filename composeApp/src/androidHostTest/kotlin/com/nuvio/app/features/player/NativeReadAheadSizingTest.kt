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
}
