package com.nuvio.app.core.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

internal sealed interface BoundedImageSourceRead {
    data class Bytes(val value: ByteArray) : BoundedImageSourceRead
    data object Empty : BoundedImageSourceRead
    data object TooLarge : BoundedImageSourceRead
}

internal suspend fun readBoundedImageSource(
    channel: ByteReadChannel,
    maxBytes: Int,
): BoundedImageSourceRead {
    require(maxBytes > 0 && maxBytes < Int.MAX_VALUE) {
        "maxBytes must be between 1 and Int.MAX_VALUE - 1"
    }
    val source = channel.readBuffer(maxBytes + 1)
    try {
        if (source.size > maxBytes.toLong()) return BoundedImageSourceRead.TooLarge
        val bytes = source.readByteArray()
        return if (bytes.isEmpty()) BoundedImageSourceRead.Empty else BoundedImageSourceRead.Bytes(bytes)
    } finally {
        source.close()
    }
}
