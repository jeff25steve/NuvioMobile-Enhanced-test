package com.nuvio.app.features.trakt

internal expect object TraktLibraryStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}