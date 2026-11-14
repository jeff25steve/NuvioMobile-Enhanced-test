package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklSyncProfileScopeTest {
    @Test
    fun profileScopeIsCurrentOnlyForTheSameProfileAndGeneration() {
        val scope = SimklSyncProfileScope(profileId = 2, generation = 8L)

        assertTrue(isSimklSyncProfileScopeCurrent(scope, currentGeneration = 8L, activeProfileId = 2))
        assertFalse(isSimklSyncProfileScopeCurrent(scope, currentGeneration = 9L, activeProfileId = 2))
        assertFalse(isSimklSyncProfileScopeCurrent(scope, currentGeneration = 8L, activeProfileId = 3))
    }

    @Test
    fun snapshotStorageKeyIsBoundToTheExplicitProfile() {
        assertEquals("simkl_sync_snapshot_1", simklSyncSnapshotStorageKey(profileId = 1))
        assertEquals("simkl_sync_snapshot_4", simklSyncSnapshotStorageKey(profileId = 4))
        assertFalse(
            simklSyncSnapshotStorageKey(profileId = 1) ==
                simklSyncSnapshotStorageKey(profileId = 4),
        )
    }
}
