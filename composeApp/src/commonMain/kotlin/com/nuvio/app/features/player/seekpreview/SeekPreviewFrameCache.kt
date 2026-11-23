package com.nuvio.app.features.player.seekpreview

/**
 * Trims an insertion-ordered seek-preview cache by both entry count and estimated retained bytes.
 *
 * Entries are removed from the eldest end, so callers can maintain LRU order by moving hits to
 * the end before calling this function. An individual frame larger than the whole budget is
 * evicted rather than allowing one oversized image to exceed the memory ceiling.
 *
 * @return the estimated bytes retained by the cache after trimming.
 */
internal fun <T> trimSeekPreviewCache(
    cache: LinkedHashMap<Long, T>,
    maxEntries: Int,
    maxBytes: Long,
    sizeInBytes: (T) -> Long,
): Long {
    val entryLimit = maxEntries.coerceAtLeast(0)
    val byteLimit = maxBytes.coerceAtLeast(0L)
    var retainedBytes = 0L

    cache.values.forEach { value ->
        val size = sizeInBytes(value).coerceAtLeast(0L)
        retainedBytes = if (Long.MAX_VALUE - retainedBytes < size) Long.MAX_VALUE else retainedBytes + size
    }

    val iterator = cache.entries.iterator()
    while ((cache.size > entryLimit || retainedBytes > byteLimit) && iterator.hasNext()) {
        val removedBytes = sizeInBytes(iterator.next().value).coerceAtLeast(0L)
        iterator.remove()
        retainedBytes = if (retainedBytes == Long.MAX_VALUE) {
            cache.values.fold(0L) { total, value ->
                val size = sizeInBytes(value).coerceAtLeast(0L)
                if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
            }
        } else {
            (retainedBytes - removedBytes).coerceAtLeast(0L)
        }
    }
    return retainedBytes
}

internal fun isSeekPreviewFrameWithinDistance(
    candidatePositionMs: Long,
    requestedPositionMs: Long,
    maxDistanceMs: Long,
): Boolean {
    if (candidatePositionMs < 0L || requestedPositionMs < 0L || maxDistanceMs < 0L) return false
    val distance = if (candidatePositionMs >= requestedPositionMs) {
        candidatePositionMs - requestedPositionMs
    } else {
        requestedPositionMs - candidatePositionMs
    }
    return distance <= maxDistanceMs
}
