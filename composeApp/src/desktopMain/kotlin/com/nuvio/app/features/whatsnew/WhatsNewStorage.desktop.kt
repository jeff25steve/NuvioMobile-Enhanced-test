package com.nuvio.app.features.whatsnew

import java.util.prefs.Preferences

actual object WhatsNewStorage {
    private const val payloadKey = "release_history_payload"
    private val preferences = Preferences.userNodeForPackage(WhatsNewStorage::class.java)

    actual fun loadPayload(): String? = preferences.get(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences.put(payloadKey, payload)
    }
}
