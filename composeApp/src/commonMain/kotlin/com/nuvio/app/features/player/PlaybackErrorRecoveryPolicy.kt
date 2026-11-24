package com.nuvio.app.features.player

private val playbackErrorCodePattern = Regex("""\\[(ERROR_CODE_[A-Z0-9_]+)]\\s*$""")
private val playbackHttpStatusPattern = Regex("""\\bHTTP\\s+(\\d{3})\\b""", RegexOption.IGNORE_CASE)

private fun playbackErrorCode(message: String?): String? =
    message?.let { playbackErrorCodePattern.find(it.trim())?.groupValues?.get(1) }

private fun playbackHttpStatus(message: String?): Int? =
    message?.let { playbackHttpStatusPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Refresh a signed URL only when the server actually rejected or expired it. */
internal fun shouldRefreshCredentialedPlaybackSource(message: String?): Boolean =
    playbackHttpStatus(message) in setOf(401, 403, 410)

/** Local server URLs can be retried for source/transport errors, but not player runtime crashes. */
internal fun shouldRetryServerSourceAfterPlaybackError(message: String?): Boolean {
    val code = playbackErrorCode(message) ?: return true
    return code.startsWith("ERROR_CODE_IO_") || code.startsWith("ERROR_CODE_PARSING_")
}

/** Only source/decoder failures justify quietly moving to another automatically selected stream. */
internal fun shouldTryNextSourceAfterPlaybackError(message: String?): Boolean {
    val code = playbackErrorCode(message) ?: return true
    return code.startsWith("ERROR_CODE_IO_") ||
        code.startsWith("ERROR_CODE_PARSING_") ||
        code.startsWith("ERROR_CODE_DECODER_") ||
        code.startsWith("ERROR_CODE_DECODING_") ||
        code.startsWith("ERROR_CODE_AUDIO_TRACK_") ||
        code == "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"
}

/** Do not hide unknown runtime failures by changing playback engines. */
internal fun shouldFailoverPlaybackEngine(message: String?): Boolean {
    val code = playbackErrorCode(message) ?: return true
    return code.startsWith("ERROR_CODE_DECODER_") ||
        code.startsWith("ERROR_CODE_DECODING_") ||
        code.startsWith("ERROR_CODE_AUDIO_TRACK_") ||
        code == "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED" ||
        code.startsWith("ERROR_CODE_PARSING_")
}

/** Keep valid URLs in the reuse cache when the player itself fails. */
internal fun shouldRemoveFailedPlaybackSourceFromCache(message: String?): Boolean {
    val code = playbackErrorCode(message) ?: return true
    return playbackHttpStatus(message) in setOf(401, 403, 404, 410) ||
        code == "ERROR_CODE_IO_FILE_NOT_FOUND"
}
