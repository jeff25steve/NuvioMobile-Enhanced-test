package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadContentRangeTest {
    @Test
    fun parsesInclusiveByteRangeAndKnownTotal() {
        val range = parseDownloadContentRange("bytes 5-10/11")

        assertEquals(5L, range?.firstByte)
        assertEquals(10L, range?.lastByteInclusive)
        assertEquals(6L, range?.byteCount)
        assertEquals(11L, range?.completeLength)
    }

    @Test
    fun preservesUnknownCompleteLengthWithoutInventingAnEnd() {
        val range = parseDownloadContentRange("bytes 42-1233/*")

        assertEquals(42L, range?.firstByte)
        assertEquals(1233L, range?.lastByteInclusive)
        assertEquals(1192L, range?.byteCount)
        assertNull(range?.completeLength)
    }

    @Test
    fun rejectsReversedAndOutOfBoundsRanges() {
        assertNull(parseDownloadContentRange("bytes 10-5/11"))
        assertNull(parseDownloadContentRange("bytes 5-10/10"))
    }

    @Test
    fun rejectsRangesWhoseInclusiveLengthOverflowsLong() {
        assertNull(parseDownloadContentRange("bytes 0-9223372036854775807/*"))
    }

    @Test
    fun rejectsNumericCompleteLengthThatOverflowsLong() {
        assertNull(parseDownloadContentRange("bytes 0-1/9223372036854775808"))
    }
}
