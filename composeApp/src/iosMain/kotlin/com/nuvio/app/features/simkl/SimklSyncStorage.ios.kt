package com.nuvio.app.features.simkl

import platform.Foundation.NSUserDefaults

internal actual object SimklSyncStorage {
    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(simklSyncSnapshotStorageKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = simklSyncSnapshotStorageKey(profileId))
    }

    actual fun removeProfile(profileId: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(simklSyncSnapshotStorageKey(profileId))
    }
}
