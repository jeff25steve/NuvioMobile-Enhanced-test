package com.nuvio.app.features.library

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGlassLayoutTest {
    @Test
    fun calendarInnerSurfaceUsesTranslucencyOnlyWhenGlassAndBackdropAreReady() {
        assertTrue(shouldRenderLibraryCalendarGlass(surfaceEnabled = true, backdropReady = true))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = true, backdropReady = false))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = false, backdropReady = true))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = false, backdropReady = false))
    }

    @Test
    fun backgroundBackdropCaptureFollowsOnlyTheVisibleLibraryGlassSurfaces() {
        assertTrue(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_SEARCH },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_SEARCH },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_CARDS },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Servers,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Servers,
                masterEnabled = true,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_FILTERS },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                masterEnabled = false,
                platformSupported = true,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_SEARCH },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                masterEnabled = true,
                platformSupported = false,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_SEARCH },
        )
    }

    @Test
    fun contentBackdropCaptureIsLimitedToSavedFiltersAndCalendar() {
        assertTrue(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = false,
                calendarGlassEnabled = false,
            ),
        )
        assertTrue(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = true,
                calendarGlassEnabled = false,
            ),
        )
        assertTrue(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = false,
                calendarGlassEnabled = true,
            ),
        )
        assertFalse(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Saved,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = false,
                calendarGlassEnabled = false,
            ),
        )
        assertFalse(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Cloud,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = true,
                calendarGlassEnabled = true,
            ),
        )
        assertFalse(
            librarySavedContentBackdropCaptureRequired(
                sourceMode = LibraryViewMode.Servers,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = true,
                calendarGlassEnabled = true,
            ),
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
    fun savedHeaderScrimStopsBeforeHeaderBottomPadding() {
        assertEquals(
            68.dp,
            librarySavedHeaderScrimHeight(headerHeight = 52.dp, topInset = 20.dp),
        )
    }

    @Test
    fun savedHeaderScrimIsLimitedToSavedModeAndEnabledLibraryGlass() {
        assertTrue(
            librarySavedHeaderUsesGlassScrim(
                sourceMode = LibraryViewMode.Saved,
                librarySurfaceGlassEnabled = true,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = false,
            ),
        )
        assertTrue(
            librarySavedHeaderUsesGlassScrim(
                sourceMode = LibraryViewMode.Saved,
                librarySurfaceGlassEnabled = false,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = false,
            ),
        )
        assertTrue(
            librarySavedHeaderUsesGlassScrim(
                sourceMode = LibraryViewMode.Saved,
                librarySurfaceGlassEnabled = false,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = true,
            ),
        )
        assertFalse(
            librarySavedHeaderUsesGlassScrim(
                sourceMode = LibraryViewMode.Saved,
                librarySurfaceGlassEnabled = false,
                savedTypeGlassEnabled = false,
                savedSortGlassEnabled = false,
            ),
        )
        assertFalse(
            librarySavedHeaderUsesGlassScrim(
                sourceMode = LibraryViewMode.Cloud,
                librarySurfaceGlassEnabled = true,
                savedTypeGlassEnabled = true,
                savedSortGlassEnabled = true,
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
    fun savedFilterOverlayReservesInitialListSpaceWheneverControlsAreVisible() {
        assertFalse(
            librarySavedControlsNeedContentInset(
                hasSavedControls = false,
            ),
        )
        assertTrue(
            librarySavedControlsNeedContentInset(
                hasSavedControls = true,
            ),
        )
    }
}
