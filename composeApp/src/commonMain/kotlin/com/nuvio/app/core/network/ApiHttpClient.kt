package com.nuvio.app.core.network

import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.IOException
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

internal expect fun createApiHttpClient(): HttpClient

internal const val API_RESPONSE_MAX_BYTES = 16 * 1024 * 1024

internal suspend fun readBoundedResponseBody(
    channel: ByteReadChannel,
    contentLength: Long?,
    maxBytes: Int = API_RESPONSE_MAX_BYTES,
): String {
    if (maxBytes < 0 || maxBytes == Int.MAX_VALUE) throw IOException("Invalid response size limit")
    if (contentLength != null && contentLength > maxBytes.toLong()) throw IOException("Response exceeds size limit")
    // One extra byte detects overflow when Content-Length is absent or inaccurate.
    val response = channel.readBuffer(maxBytes + 1)
    try {
        if (response.size > maxBytes.toLong()) throw IOException("Response exceeds size limit")
        return response.readByteArray().decodeToString()
    } finally {
        response.close()
    }
}
