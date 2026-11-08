package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CancellationException

/**
 * Captures ordinary failures from a suspending operation without converting
 * coroutine cancellation into a recoverable result.
 */
internal suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
