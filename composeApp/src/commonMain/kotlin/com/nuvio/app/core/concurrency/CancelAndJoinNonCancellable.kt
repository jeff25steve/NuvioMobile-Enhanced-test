package com.nuvio.app.core.concurrency

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

/**
 * Cancels the supplied operations and waits until their finally blocks have completed.
 *
 * The wait is protected from caller cancellation because callers use it as a boundary
 * before clearing or replacing state that the cancelled operations may still mutate.
 */
internal suspend fun cancelAndJoinNonCancellable(jobs: Collection<Job>) {
    if (jobs.isEmpty()) return
    jobs.forEach { it.cancel() }
    withContext(NonCancellable) {
        jobs.joinAll()
    }
    currentCoroutineContext().ensureActive()
}

internal suspend fun cancelAndJoinNonCancellable(job: Job?) {
    if (job == null) return
    cancelAndJoinNonCancellable(listOf(job))
}
