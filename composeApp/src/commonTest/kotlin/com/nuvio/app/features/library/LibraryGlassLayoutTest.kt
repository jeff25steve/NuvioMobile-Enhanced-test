package com.nuvio.app.features.library

import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGlassLayoutTest {
    @Test
    fun calendarGlassAloneKeepsLibraryBackdropCaptureActive() {
        assertTrue(
            libraryBackdropCaptureRequired(masterEnabled = true) {
                it == LiquidGlassSurface.LIBRARY_CALENDAR
            },
        )
        assertFalse(
            libraryBackdropCaptureRequired(masterEnabled = false) {
                it == LiquidGlassSurface.LIBRARY_CALENDAR
            },
        )
        assertFalse(
            libraryBackdropCaptureRequired(masterEnabled = true) { false },
        )
    }

    @Test
    fun liveCalendarUsesLibraryContentBackdropOnlyWhenGlassIsEnabledInSavedMode() {
        assertTrue(
            shouldUseLibraryContentBackdropForCalendar(
                sourceMode = LibraryViewMode.Saved,
                calendarGlassEnabled = true,
            ),
        )
        assertFalse(
            shouldUseLibraryContentBackdropForCalendar(
                sourceMode = LibraryViewMode.Saved,
                calendarGlassEnabled = false,
            ),
        )
        assertFalse(
            shouldUseLibraryContentBackdropForCalendar(
                sourceMode = LibraryViewMode.Cloud,
                calendarGlassEnabled = true,
            ),
        )
    }

    @Test
    fun artworkHeaderFadeIsOnlyUsedForCloudLibraryWithAnActiveBackdrop() {
        assertTrue(
            libraryHeaderUsesArtworkBackdrop(
                sourceMode = LibraryViewMode.Cloud,
                artworkBackgroundActive = true,
            ),
        )
        assertFalse(
            libraryHeaderUsesArtworkBackdrop(
                sourceMode = LibraryViewMode.Saved,
                artworkBackgroundActive = true,
            ),
        )
        assertFalse(
            libraryHeaderUsesArtworkBackdrop(
                sourceMode = LibraryViewMode.Cloud,
                artworkBackgroundActive = false,
            ),
        )
    }

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
