package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktLibraryRequestGuardTest {
    @Test
    fun `request is current only for the same active profile and generation`() {
        assertTrue(isCurrentTraktLibraryRequest(2, 7L, 2, 7L))
        assertFalse(isCurrentTraktLibraryRequest(1, 7L, 2, 7L))
        assertFalse(isCurrentTraktLibraryRequest(2, 6L, 2, 7L))
    }
}
