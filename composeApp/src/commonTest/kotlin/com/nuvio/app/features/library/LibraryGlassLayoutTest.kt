package com.nuvio.app.features.library

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGlassLayoutTest {
    @Test
    fun calendarDayAccessibilityLabelIncludesFullDateTodayAndReleaseCount() {
        assertEquals(
            "November 12, 2026, Today, 2 releases",
            libraryCalendarDayAccessibilityDescription(
                dateLabel = "November 12, 2026",
                todayLabel = "Today",
                releaseCountLabel = "2 releases",
            ),
        )
    }

    @Test
    fun calendarDayAccessibilityLabelOmitsUnavailableContext() {
        assertEquals(
            "November 12, 2026",
            libraryCalendarDayAccessibilityDescription(
                dateLabel = "November 12, 2026",
                todayLabel = null,
                releaseCountLabel = null,
            ),
        )
    }

    @Test
    fun calendarInnerSurfaceUsesTranslucencyOnlyWhenGlassAndBackdropAreReady() {
        assertTrue(shouldRenderLibraryCalendarGlass(surfaceEnabled = true, backdropReady = true))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = true, backdropReady = false))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = false, backdropReady = true))
        assertFalse(shouldRenderLibraryCalendarGlass(surfaceEnabled = false, backdropReady = false))
    }

    @Test
    fun libraryBackdropCaptureRequiresTheActiveSurfaceOrVisibleCalendar() {
        assertTrue(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Saved,
                hasSavedControls = true,
                showReleaseCalendar = true,
            ) { it == LiquidGlassSurface.LIBRARY_CALENDAR },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Saved,
                hasSavedControls = true,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_CALENDAR },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Saved,
                hasSavedControls = true,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_CARDS },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Saved,
                hasSavedControls = false,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Saved,
                hasSavedControls = true,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Cloud,
                hasSavedControls = false,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_CLOUD_CARDS },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Cloud,
                hasSavedControls = false,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER },
        )
        assertTrue(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = true,
                sourceMode = LibraryViewMode.Servers,
                hasSavedControls = false,
                showReleaseCalendar = false,
            ) { it == LiquidGlassSurface.LIBRARY },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = false,
                platformSupported = true,
                sourceMode = LibraryViewMode.Cloud,
                hasSavedControls = false,
                showReleaseCalendar = true,
            ) { true },
        )
        assertFalse(
            libraryBackdropCaptureRequired(
                masterEnabled = true,
                platformSupported = false,
                sourceMode = LibraryViewMode.Cloud,
                hasSavedControls = false,
                showReleaseCalendar = true,
            ) { true },
        )
    }

    @Test
    fun savedContentBackdropCaptureRequiresVisibleSavedGlassConsumers() {
        fun capture(
            sourceMode: LibraryViewMode = LibraryViewMode.Saved,
            hasSavedControls: Boolean = true,
            showReleaseCalendar: Boolean = false,
            masterEnabled: Boolean = true,
            platformSupported: Boolean = true,
            enabledSurface: LiquidGlassSurface,
        ) = libraryContentBackdropCaptureRequired(
            masterEnabled = masterEnabled,
            platformSupported = platformSupported,
            sourceMode = sourceMode,
            hasSavedControls = hasSavedControls,
            showReleaseCalendar = showReleaseCalendar,
        ) { it == enabledSurface }

        assertTrue(capture(enabledSurface = LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER))
        assertTrue(capture(enabledSurface = LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER))
        assertFalse(capture(hasSavedControls = false, enabledSurface = LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER))
        assertTrue(capture(showReleaseCalendar = true, enabledSurface = LiquidGlassSurface.LIBRARY_CALENDAR))
        assertFalse(capture(showReleaseCalendar = false, enabledSurface = LiquidGlassSurface.LIBRARY_CALENDAR))
        // Saved source-switch glass now samples the same scrolling poster layer as the pinned filters.
        assertTrue(capture(enabledSurface = LiquidGlassSurface.LIBRARY))
        assertFalse(capture(sourceMode = LibraryViewMode.Cloud, enabledSurface = LiquidGlassSurface.LIBRARY_CLOUD_CARDS))
        assertFalse(capture(sourceMode = LibraryViewMode.Servers, enabledSurface = LiquidGlassSurface.LIBRARY))
        assertFalse(capture(masterEnabled = false, enabledSurface = LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER))
        assertFalse(capture(platformSupported = false, enabledSurface = LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER))
    }

    @Test
    fun libraryStaticBackdropCaptureRunsOnlyForStaticBackdropConsumers() {
        fun capture(
            sourceMode: LibraryViewMode,
            enabledSurface: LiquidGlassSurface,
            masterEnabled: Boolean = true,
            platformSupported: Boolean = true,
        ) = libraryStaticBackdropCaptureRequired(
            masterEnabled = masterEnabled,
            platformSupported = platformSupported,
            sourceMode = sourceMode,
        ) { it == enabledSurface }

        assertTrue(capture(LibraryViewMode.Saved, LiquidGlassSurface.LIBRARY))
        assertTrue(capture(LibraryViewMode.Cloud, LiquidGlassSurface.LIBRARY))
        assertTrue(capture(LibraryViewMode.Servers, LiquidGlassSurface.LIBRARY))
        assertFalse(capture(LibraryViewMode.Saved, LiquidGlassSurface.LIBRARY_SAVED_TYPE_FILTER))
        assertFalse(capture(LibraryViewMode.Saved, LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER))
        assertFalse(capture(LibraryViewMode.Saved, LiquidGlassSurface.LIBRARY_CALENDAR))
        assertTrue(capture(LibraryViewMode.Cloud, LiquidGlassSurface.LIBRARY_CLOUD_FILTERS))
        assertTrue(capture(LibraryViewMode.Cloud, LiquidGlassSurface.LIBRARY_CLOUD_SEARCH))
        assertTrue(capture(LibraryViewMode.Cloud, LiquidGlassSurface.LIBRARY_CLOUD_CARDS))
        assertFalse(capture(LibraryViewMode.Cloud, LiquidGlassSurface.LIBRARY_SAVED_SORT_FILTER))
        assertFalse(capture(LibraryViewMode.Servers, LiquidGlassSurface.LIBRARY_CLOUD_CARDS))
        assertFalse(
            capture(
                LibraryViewMode.Cloud,
                LiquidGlassSurface.LIBRARY_CLOUD_CARDS,
                masterEnabled = false,
            ),
        )
        assertFalse(
            capture(
                LibraryViewMode.Cloud,
                LiquidGlassSurface.LIBRARY_CLOUD_CARDS,
                platformSupported = false,
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
    fun cloudPreviewPlacesItsPinnedToolbarBelowTheFixedHeader() {
        assertEquals(
            132.dp,
            libraryCloudPreviewViewportTopInset(
                sourceMode = LibraryViewMode.Cloud,
                headerHeight = 132.dp,
            ),
        )
        assertEquals(
            0.dp,
            libraryCloudPreviewViewportTopInset(
                sourceMode = LibraryViewMode.Saved,
                headerHeight = 132.dp,
            ),
        )
        assertEquals(
            0.dp,
            libraryCloudPreviewViewportTopInset(
                sourceMode = LibraryViewMode.Servers,
                headerHeight = 132.dp,
            ),
        )
    }

    @Test
    fun savedHeaderScrimStopsBeforeTheTitleHeadersBottomSpacing() {
        assertEquals(4.dp, librarySavedHeaderScrimBottomInset(scrimEnabled = true))
        assertEquals(0.dp, librarySavedHeaderScrimBottomInset(scrimEnabled = false))
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
    fun cloudHeaderScrimUsesTheSameDarkArtworkFadeAsTheLiveLibrary() {
        assertEquals(
            listOf(
                0f to Color.Black.copy(alpha = 0.38f),
                0.58f to Color.Black.copy(alpha = 0.18f),
                1f to Color.Transparent,
            ),
            libraryCloudHeaderScrimColorStops(),
        )
    }

    @Test
    fun savedTitleHeaderKeepsItsOpaqueBackingScopedToTheTitle() {
        assertTrue(libraryTitleHeaderNeedsOpaqueBackground(LibraryViewMode.Saved))
        assertFalse(libraryTitleHeaderNeedsOpaqueBackground(LibraryViewMode.Cloud))
        assertFalse(libraryTitleHeaderNeedsOpaqueBackground(LibraryViewMode.Servers))
    }

    @Test
    fun cloudStickyToolbarRevealsCustomArtworkAndKeepsBlackDefaultBackdrop() {
        assertEquals(
            Color.Transparent,
            libraryCloudToolbarBackgroundColor(artworkBackgroundActive = true),
        )
        assertEquals(
            Color.Black,
            libraryCloudToolbarBackgroundColor(artworkBackgroundActive = false),
        )
    }

    @Test
    fun savedPinnedGlassChromeUsesScrollingContentBackdropOnlyInSavedMode() {
        assertTrue(
            libraryPinnedChromeUsesContentBackdrop(
                sourceMode = LibraryViewMode.Saved,
                glassEnabled = true,
            ),
        )
        assertFalse(
            libraryPinnedChromeUsesContentBackdrop(
                sourceMode = LibraryViewMode.Saved,
                glassEnabled = false,
            ),
        )
        assertFalse(
            libraryPinnedChromeUsesContentBackdrop(
                sourceMode = LibraryViewMode.Cloud,
                glassEnabled = true,
            ),
        )
        assertFalse(
            libraryPinnedChromeUsesContentBackdrop(
                sourceMode = LibraryViewMode.Servers,
                glassEnabled = true,
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

    @Test
    fun librarySourceSwitchMatchesSearchControlHeightOnNativeAndComposePaths() {
        assertEquals(
            48.dp,
            librarySourceSwitchMinimumHeight(nativeSegmentedControlEnabled = true),
        )
        assertEquals(
            OutlinedTextFieldDefaults.MinHeight,
            librarySourceSwitchMinimumHeight(nativeSegmentedControlEnabled = false),
        )
    }

    @Test
    fun savedScrollViewportReservesTheSourcePillAndPinnedFiltersBeforeTheFirstPoster() {
        assertEquals(
            138.dp,
            libraryContentTopPadding(
                hasSavedControls = true,
                sourceSwitchInset = 52.dp,
                savedControlsInset = 72.dp,
            ),
        )
        assertEquals(
            52.dp,
            libraryContentTopPadding(
                hasSavedControls = false,
                sourceSwitchInset = 52.dp,
                savedControlsInset = 72.dp,
            ),
        )
    }

}
