package com.nuvio.app.features.downloads

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDownloadExportTest {
    @Test
    fun unknownDestinationLengthDoesNotRejectCompletedCopy() {
        assertFalse(isDocumentCopyLengthMismatch(sourceLength = 4_096L, destinationLength = 0L))
    }

    @Test
    fun knownDestinationLengthMustMatchSource() {
        assertTrue(isDocumentCopyLengthMismatch(sourceLength = 4_096L, destinationLength = 4_095L))
        assertFalse(isDocumentCopyLengthMismatch(sourceLength = 4_096L, destinationLength = 4_096L))
    }

    @Test
    fun emptySourceAndDestinationAreAccepted() {
        assertFalse(isDocumentCopyLengthMismatch(sourceLength = 0L, destinationLength = 0L))
    }
}
