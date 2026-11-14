package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey

internal data class SimklSyncProfileScope(
    val profileId: Int,
    val generation: Long,
)

internal fun isSimklSyncProfileScopeCurrent(
    scope: SimklSyncProfileScope,
    currentGeneration: Long,
    activeProfileId: Int,
): Boolean =
    scope.generation == currentGeneration && scope.profileId == activeProfileId

internal fun simklSyncSnapshotStorageKey(profileId: Int): String =
    ProfileScopedKey.of("simkl_sync_snapshot", profileId)

internal expect object SimklSyncStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun removeProfile(profileId: Int)
}
