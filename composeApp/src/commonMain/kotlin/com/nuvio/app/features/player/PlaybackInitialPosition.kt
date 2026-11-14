package com.nuvio.app.features.player

/**
 * Formats an MPV per-file start option without locale-dependent decimal separators.
 * A null result means playback should start at the beginning.
 */
internal fun mpvStartPositionSeconds(positionMs: Long): String? {
    if (positionMs <= 0L) return null
    val wholeSeconds = positionMs / 1_000L
    val fractionalMilliseconds = (positionMs % 1_000L).toString().padStart(3, '0')
    return "$wholeSeconds.$fractionalMilliseconds"
}
