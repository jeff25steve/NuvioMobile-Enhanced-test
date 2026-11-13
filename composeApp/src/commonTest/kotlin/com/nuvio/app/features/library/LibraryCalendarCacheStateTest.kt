package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryCalendarCacheStateTest {
    @Test
    fun `month warm completion only clears its own cache loading state`() {
        assertTrue(
            shouldClearLibraryCalendarWarmingState(
                currentCacheKey = "profile-a:months:items",
                requestedCacheKey = "profile-a:months:items",
                isWarming = true,
                fullWarmInProgress = false,
            ),
        )
        assertFalse(
            shouldClearLibraryCalendarWarmingState(
                currentCacheKey = "profile-b:months:items",
                requestedCacheKey = "profile-a:months:items",
                isWarming = true,
                fullWarmInProgress = false,
            ),
        )
        assertFalse(
            shouldClearLibraryCalendarWarmingState(
                currentCacheKey = "profile-a:months:items",
                requestedCacheKey = "profile-a:months:items",
                isWarming = true,
                fullWarmInProgress = true,
            ),
        )
        assertFalse(
            shouldClearLibraryCalendarWarmingState(
                currentCacheKey = "profile-a:months:items",
                requestedCacheKey = "profile-a:months:items",
                isWarming = false,
                fullWarmInProgress = false,
            ),
        )
    }
}
