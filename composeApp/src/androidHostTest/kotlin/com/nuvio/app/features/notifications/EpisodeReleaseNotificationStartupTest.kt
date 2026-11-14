package com.nuvio.app.features.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeReleaseNotificationStartupTest {
    @Test
    fun initializeDoesNotCreateNotificationChannelBeforeItIsNeeded() {
        val context = RuntimeEnvironment.getApplication()
        val notificationManager = checkNotNull(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager,
        )
        notificationManager.deleteNotificationChannel(EpisodeReleaseNotificationPlatform.channelId)

        EpisodeReleaseNotificationPlatform.initialize(context)

        assertNull(notificationManager.getNotificationChannel(EpisodeReleaseNotificationPlatform.channelId))
    }
}
