package com.nuvio.app.core.ui

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

internal sealed interface BoundedAnimatedSourceRead {
    data class Bytes(val value: ByteArray) : BoundedAnimatedSourceRead
    data object Empty : BoundedAnimatedSourceRead
    data object TooLarge : BoundedAnimatedSourceRead
}

/**
 * Reads at most [maxBytes] plus one sentinel byte so unknown or inaccurate
 * Content-Length headers cannot make animated-source buffering unbounded.
 *
 * The caller must cancel the owning HTTP response when [TooLarge] is returned.
 */
internal suspend fun readBoundedAnimatedSource(
    channel: ByteReadChannel,
    maxBytes: Int,
): BoundedAnimatedSourceRead {
    require(maxBytes > 0 && maxBytes < Int.MAX_VALUE) {
        "maxBytes must be between 1 and Int.MAX_VALUE - 1"
    }

    val source = channel.readBuffer(maxBytes + 1)
    try {
        if (source.size > maxBytes.toLong()) return BoundedAnimatedSourceRead.TooLarge
        val bytes = source.readByteArray()
        return if (bytes.isEmpty()) {
            BoundedAnimatedSourceRead.Empty
        } else {
            BoundedAnimatedSourceRead.Bytes(bytes)
        }
    } finally {
        source.close()
    }
}
