package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchProgressPersistSequenceTest {
    @Test
    fun aWriteForOneProfileDoesNotSupersedeAnotherProfilesPendingWrite() {
        val tracker = WatchProgressPersistSequence()
        tracker.markPersisted(profileId = 2, sequence = 2L)

        // Profile 1's sequence is older globally, but its own stored snapshot is still old.
        assertFalse(tracker.isStale(profileId = 1, sequence = 1L))
        assertTrue(tracker.isStale(profileId = 2, sequence = 2L))
    }

    @Test
    fun onlyANewerWriteForTheSameProfileMakesAnOlderWriteStale() {
        val tracker = WatchProgressPersistSequence()
        tracker.markPersisted(profileId = 1, sequence = 5L)

        assertTrue(tracker.isStale(profileId = 1, sequence = 4L))
        assertFalse(tracker.isStale(profileId = 2, sequence = 4L))
    }

    @Test
    fun markingAnOlderWriteCannotMoveTheProfileWatermarkBackwards() {
        val tracker = WatchProgressPersistSequence()
        tracker.markPersisted(profileId = 1, sequence = 5L)
        tracker.markPersisted(profileId = 1, sequence = 3L)

        assertTrue(tracker.isStale(profileId = 1, sequence = 4L))
        assertFalse(tracker.isStale(profileId = 1, sequence = 6L))
    }

    @Test
    fun accountClearInvalidatesOutstandingWritesAcrossEveryProfile() {
        val tracker = WatchProgressPersistSequence()
        tracker.markPersisted(profileId = 1, sequence = 4L)
        tracker.invalidateThrough(8L)

        assertTrue(tracker.isStale(profileId = 1, sequence = 5L))
        assertTrue(tracker.isStale(profileId = 2, sequence = 8L))
        assertFalse(tracker.isStale(profileId = 1, sequence = 9L))
    }
}
