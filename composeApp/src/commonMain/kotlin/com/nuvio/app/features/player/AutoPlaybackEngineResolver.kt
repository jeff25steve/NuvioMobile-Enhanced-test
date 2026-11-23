package com.nuvio.app.features.player

/**
 * Mirrors NuvioTV's `resolveAutoInternalPlayerEngine()`: anime goes to libmpv, everything else to
 * ExoPlayer, HDR/Dolby Vision takes precedence over anime.
 */
internal fun resolveAutoPlaybackEngine(
    playbackEngineSetting: AndroidPlaybackEngine,
    videoId: String?,
    streamFilename: String?,
    streamName: String?,
    streamDescription: String?,
    contentTitle: String?,
    genres: List<String>,
    country: String?,
): AndroidPlaybackEngine? {
    if (playbackEngineSetting != AndroidPlaybackEngine.Auto) return null

    val streamMetadataText = buildString {
        streamFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        streamDescription?.let { appendLine(it) }
        append(contentTitle.orEmpty())
    }

    // HDR / Dolby Vision takes precedence: libmpv would improperly tonemap it to SDR.
    if (HDR_OR_DOLBY_VISION_REGEX.containsMatchIn(streamMetadataText)) {
        return AndroidPlaybackEngine.ExoPlayer
    }

    if (isAnimeContent(videoId, genres, country)) return AndroidPlaybackEngine.Libmpv

    return AndroidPlaybackEngine.ExoPlayer
}

internal fun isAnimeContent(
    videoId: String?,
    genres: List<String>,
    country: String?,
): Boolean {
    val hasAnimeId = videoId?.startsWith("kitsu:") == true ||
        videoId?.startsWith("mal:") == true ||
        videoId?.startsWith("anilist:") == true
    if (hasAnimeId) return true

    if (genres.any { it.equals("anime", ignoreCase = true) }) return true

    return genres.any { it.equals("animation", ignoreCase = true) } && isJapan(country)
}

// Media filenames/addon metadata commonly use standardized transfer names as well as
// consumer labels. Match complete tokens (not DVDRip/PQ10) while accepting common separators.
private val HDR_OR_DOLBY_VISION_REGEX = Regex(
    """(?i)(?:^|[^A-Za-z0-9])(?:HDR(?:10(?:\+|PLUS)?)?|HLG|PQ|DV|DOVI|DOLBY[\s._-]*VISION|SMPTE[\s._-]*ST[\s._-]*2084|ST[\s._-]*2084|BT[\s._-]*2100|ARIB[\s._-]*STD[\s._-]*B67)(?=$|[^A-Za-z0-9])""",
)

// TMDB enrichment rewrites `country` as comma-joined ISO codes ("US, GB"), addon metadata as
// full names ("Japan") — match whole comma-separated tokens.
private fun isJapan(country: String?): Boolean =
    country?.split(',')?.any { token ->
        val value = token.trim()
        value.equals("japan", ignoreCase = true) || value.equals("jp", ignoreCase = true)
    } == true
