package com.nuvio.app.features.notifications

internal expect object EpisodeReleaseNotificationPlatform {
    suspend fun notificationsAuthorized(): Boolean
    suspend fun requestAuthorization(): Boolean
    suspend fun scheduleEpisodeReleaseNotifications(
        profileId: Int,
        requests: List<EpisodeReleaseNotificationRequest>,
    )
    suspend fun clearScheduledEpisodeReleaseNotifications(profileId: Int)
    suspend fun clearAllEpisodeReleaseNotifications()
    suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest)
}