package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGlassLayoutTest {
    @Test
    fun glassFilterOverlayReservesSpaceOnlyWhenSavedControlsAreVisibleAndGlassEnabled() {
        assertFalse(
            libraryGlassControlsNeedContentInset(
                hasSavedControls = false,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = false,
            ),
        )
        assertFalse(
            libraryGlassControlsNeedContentInset(
                hasSavedControls = true,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = false,
            ),
        )
        assertTrue(
            libraryGlassControlsNeedContentInset(
                hasSavedControls = true,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = false,
            ),
        )
        assertTrue(
            libraryGlassControlsNeedContentInset(
                hasSavedControls = true,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = true,
            ),
        )
    }
}
