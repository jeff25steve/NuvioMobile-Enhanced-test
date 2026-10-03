package com.nuvio.app.features.whatsnew

import android.content.Context

actual object WhatsNewStorage {
    private const val preferencesName = "nuvio_whats_new"
    private const val payloadKey = "release_history_payload"

    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE,
        )
    }

    actual fun loadPayload(): String? = preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences?.edit()?.putString(payloadKey, payload)?.apply()
    }
}
