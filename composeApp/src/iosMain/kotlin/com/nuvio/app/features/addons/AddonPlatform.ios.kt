package com.nuvio.app.features.addons

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults



actual object AddonStorage {
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(
            urls.joinToString(separator = "\n"),
            forKey = "${addonUrlsKey}_$profileId",
        )
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonEnabledStatesKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${addonEnabledStatesKey}_$profileId",
        )
    }
}

private fun parseEnabledStateLine(line: String): Pair<String, Boolean>? {
    val url = line.substringBefore("\t").trim().takeIf { it.isNotEmpty() } ?: return null
    val rawEnabled = line.substringAfter("\t", "true").trim().lowercase()
    val enabled = when (rawEnabled) {
        "false" -> false
        else -> true
    }
    return url to enabled
}

private fun createAddonHttpClient(followRedirects: Boolean): HttpClient =
    HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
        this.followRedirects = followRedirects
    }

private val addonHttpClient = createAddonHttpClient(followRedirects = true)
private val addonHttpClientWithoutRedirects by lazy {
    createAddonHttpClient(followRedirects = false)
}

actual suspend fun httpGetText(url: String): String =
    addonHttpClient
        .get(url) {
            accept(ContentType.Application.Json)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpPostJson(url: String, body: String): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    addonHttpClient
        .get(url) {
            accept(ContentType.Application.Json)
            headers.forEach { (key, value) ->
                header(key, value)
            }
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            headers.forEach { (key, value) ->
                header(key, value)
            }
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

internal data class LimitedAddonResponseBody(
    val bytes: ByteArray,
    val truncated: Boolean,
)

/**
 * Read at most [maxBytes] from a response channel, without materialising the full response.
 * One extra byte is probed so chunked/unknown-length responses retain the existing truncation signal.
 */
internal suspend fun readAddonResponseBodyAtMost(
    channel: ByteReadChannel,
    maxBytes: Int,
): LimitedAddonResponseBody {
    val limit = maxBytes.coerceAtLeast(0)
    var buffer = ByteArray(minOf(limit, 16 * 1024))
    var totalRead = 0

    while (totalRead < limit) {
        if (totalRead == buffer.size) {
            val nextCapacity = if (buffer.size >= limit - buffer.size) {
                limit
            } else {
                buffer.size * 2
            }
            buffer = buffer.copyOf(maxOf(totalRead + 1, nextCapacity).coerceAtMost(limit))
        }

        val read = channel.readAvailable(
            buffer = buffer,
            offset = totalRead,
            length = minOf(buffer.size - totalRead, limit - totalRead),
        )
        when {
            read < 0 -> {
                channel.closedCause?.let { throw it }
                break
            }
            read == 0 -> {
                if (!channel.awaitContent()) {
                    channel.closedCause?.let { throw it }
                    break
                }
            }
            else -> totalRead += read
        }
    }

    val truncated = if (totalRead == limit) channel.hasMoreResponseBytes() else false
    val bytes = if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
    return LimitedAddonResponseBody(bytes = bytes, truncated = truncated)
}

private suspend fun ByteReadChannel.hasMoreResponseBytes(): Boolean {
    val probe = ByteArray(1)
    while (true) {
        val read = readAvailable(buffer = probe, offset = 0, length = 1)
        when {
            read < 0 -> {
                closedCause?.let { throw it }
                return false
            }
            read > 0 -> return true
            !awaitContent() -> {
                closedCause?.let { throw it }
                return false
            }
        }
    }
}

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
    bodyBytes: ByteArray?,
): RawHttpResponse {
    val client = if (followRedirects) addonHttpClient else addonHttpClientWithoutRedirects
    val requestMethod = method.uppercase()

    return client.prepareRequest {
        url(url)
        this.method = HttpMethod.parse(requestMethod)
        headers.forEach { (key, value) ->
            header(key, value)
        }
        if (addonRequestMethodAllowsBody(requestMethod)) {
            setBody(bodyBytes ?: body)
        }
    }.execute { response ->
        val limitedBody = readAddonResponseBodyAtMost(
            channel = response.bodyAsChannel(),
            maxBytes = maxResponseBodyBytes,
        )
        val decoded = limitedBody.bytes.decodeToString()
        RawHttpResponse(
            status = response.status.value,
            statusText = response.status.description,
            url = response.call.request.url.toString(),
            body = if (limitedBody.truncated) "$decoded\n...[truncated]" else decoded,
            bodyBytes = limitedBody.bytes,
            headers = response.headers.entries().associate { (name, values) ->
                name.lowercase() to values.joinToString(",")
            },
        )
    }
}
