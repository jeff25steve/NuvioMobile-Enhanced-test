package com.nuvio.app.core.concurrency

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Maps a list in sequential batches, with no more than [batchSize] child coroutines
 * created at once. Results retain the source order within and across batches.
 */
internal suspend fun <T, R> mapInBatchesConcurrently(
    items: List<T>,
    batchSize: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    require(batchSize > 0) { "Batch size must be positive" }

    val results = ArrayList<R>(items.size)
    var startIndex = 0
    while (startIndex < items.size) {
        val endIndex = startIndex + minOf(batchSize, items.size - startIndex)
        val batchResults = (startIndex until endIndex)
            .map { index -> async { transform(items[index]) } }
            .awaitAll()
        results.addAll(batchResults)
        startIndex = endIndex
    }
    results
}
