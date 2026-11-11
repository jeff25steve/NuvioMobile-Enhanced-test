package com.nuvio.app.features.player

internal data class SafeMpvLogMessage(
    val prefix: String,
    val message: String,
) {
    fun formatted(): String = "[" + prefix + "] " + message
}

internal fun sanitizeMpvLogMessage(prefix: String, value: String): SafeMpvLogMessage? {
    var message = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(MPV_WHITESPACE, " ")
        .trim()
    if (message.isEmpty() || MPV_COOKIE_HEADER.containsMatchIn(message)) return null

    message = MPV_SOURCE_URL.replace(message, "[source URL]")
    message = MPV_QUERY_STRING.replace(message) { match ->
        match.groupValues[1] + "?[redacted]"
    }
    message = MPV_CREDENTIALS.replace(message, "[redacted]@")
    message = MPV_AUTH_HEADER.replace(message) { match ->
        match.groupValues[1] + ": [redacted]"
    }
    message = MPV_SECRET_ASSIGNMENT.replace(message) { match ->
        match.groupValues[1] + "=[redacted]"
    }
    message = MPV_BEARER_TOKEN.replace(message, "Bearer [redacted]")
    message = MPV_JWT.replace(message, "[redacted]")
    message = message.take(MPV_DIAGNOSTIC_MESSAGE_LIMIT)

    val safePrefix = prefix
        .filter { it.isLetterOrDigit() || it in "._/-" }
        .take(MPV_DIAGNOSTIC_PREFIX_LIMIT)
        .ifBlank { "mpv" }
    return message.takeIf(String::isNotBlank)?.let { SafeMpvLogMessage(safePrefix, it) }
}

internal fun sanitizeMpvErrorText(value: String?): String? =
    value?.let { sanitizeMpvLogMessage("mpv", it)?.message }

internal const val MPV_DIAGNOSTIC_MESSAGE_LIMIT = 160
internal const val MAX_RECENT_MPV_ERROR_LOGS = 3

private val MPV_WHITESPACE = Regex("\\s+")
private val MPV_COOKIE_HEADER = Regex("""(?i)\b(?:cookie|set-cookie)\s*:""")
private val MPV_SOURCE_URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
private val MPV_QUERY_STRING = Regex("""([^\s"'<>?]+)\?[^\s"'<>]+""")
private val MPV_CREDENTIALS = Regex("""(?i)\b[^/\s:@]+:[^/\s@]+@""")
private val MPV_AUTH_HEADER = Regex(
    """(?i)\b(authorization|proxy-authorization)\s*:\s*(?:(?:bearer|basic)\s+)?[^\s,;]+"""
)
private val MPV_SECRET_ASSIGNMENT = Regex(
    """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|signature|sig|jwt)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)"""
)
private val MPV_BEARER_TOKEN = Regex("""(?i)\bBearer\s+[^\s,;]+""")
private val MPV_JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")

private const val MPV_DIAGNOSTIC_PREFIX_LIMIT = 32
