package com.nuvio.app.features.simkl

import android.content.Context
import android.content.SharedPreferences

internal actual object SimklSyncStorage {
    private const val PREFERENCES_NAME = "nuvio_simkl_sync"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(simklSyncSnapshotStorageKey(profileId), null)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences?.edit()?.putString(simklSyncSnapshotStorageKey(profileId), payload)?.apply()
    }

    actual fun removeProfile(profileId: Int) {
        preferences?.edit()
            ?.remove(simklSyncSnapshotStorageKey(profileId))
            ?.apply()
    }
}
