package com.nuvio.app.features.whatsnew

import platform.Foundation.NSUserDefaults

actual object WhatsNewStorage {
    private const val payloadKey = "nuvio_whats_new_release_history_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
