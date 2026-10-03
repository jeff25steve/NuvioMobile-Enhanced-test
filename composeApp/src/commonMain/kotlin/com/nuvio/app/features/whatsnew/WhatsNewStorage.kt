package com.nuvio.app.features.whatsnew

internal expect object WhatsNewStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
