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

    @Test
    fun `persisted calendar snapshots are only reused for the matching profile cache key`() {
        assertTrue(
            shouldUseLibraryReleaseCalendarSnapshot(
                requestedCacheKey = "profile-a:months:items",
                snapshotCacheKey = "profile-a:months:items",
            ),
        )
        assertFalse(
            shouldUseLibraryReleaseCalendarSnapshot(
                requestedCacheKey = "profile-b:months:items",
                snapshotCacheKey = "profile-a:months:items",
            ),
        )
        assertFalse(
            shouldUseLibraryReleaseCalendarSnapshot(
                requestedCacheKey = "profile-a:months:items",
                snapshotCacheKey = null,
            ),
        )
    }

    @Test
    fun `only the current warm generation and cache key may publish results`() {
        assertTrue(
            shouldApplyLibraryCalendarWarmResult(
                requestedCacheKey = "profile-a:months:items",
                currentCacheKey = "profile-a:months:items",
                requestedGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            shouldApplyLibraryCalendarWarmResult(
                requestedCacheKey = "profile-a:months:items",
                currentCacheKey = "profile-a:months:items",
                requestedGeneration = 7L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            shouldApplyLibraryCalendarWarmResult(
                requestedCacheKey = "profile-a:months:items",
                currentCacheKey = "profile-b:months:items",
                requestedGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
    }
}
