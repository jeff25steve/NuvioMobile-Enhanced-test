package com.nuvio.app.features.notifications

import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpisodeReleaseNotificationWorker(
    appContext: android.content.Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result =
        try {
            EpisodeReleaseNotificationPlatform.deliverDueAndScheduleNext(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
}
