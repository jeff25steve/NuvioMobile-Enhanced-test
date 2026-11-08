package com.nuvio.app.features.notifications

internal expect object EpisodeReleaseNotificationsStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}