package com.nuvio.app.features.player

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val audioProfiles: List<String> = emptyList(),
    val hdr: List<String> = emptyList(),
    val bitDepth: Int? = null,
)

internal fun mpvPlaybackHdrFormat(
    dvProfile: String?,
    videoCodec: String?,
    videoDecoder: String?,
    codecProfile: String?,
    gamma: String?,
    colorMatrix: String?,
): String {
    val profile = dvProfile?.trim()?.lowercase().orEmpty()
    val hasDolbyVisionProfile = profile.isNotBlank() &&
        profile !in setOf("none", "unknown", "0", "false")
    val hasDolbyVisionCodec = listOf(videoCodec, videoDecoder, codecProfile)
        .filterNotNull()
        .any { value ->
            val normalized = value.lowercase()
            normalized.contains("dovi") ||
                listOf("dvhe", "dvh1", "dvav", "dva1", "dav1").any { prefix -> normalized.contains(prefix) }
        }
    if (hasDolbyVisionProfile || hasDolbyVisionCodec || colorMatrix.equals("dolbyvision", ignoreCase = true)) {
        return "dolby_vision"
    }

    return when (gamma?.trim()?.lowercase()) {
        "pq" -> "hdr"
        "hlg" -> "hlg"
        "sdr" -> "sdr"
        else -> ""
    }
}

internal fun mpvPlaybackColorRange(colorLevels: String?): String? = when (colorLevels?.trim()?.lowercase()) {
    "full" -> "Full"
    "limited" -> "Limited"
    else -> null
}

internal fun mpvMediaInfo(property: (String) -> String?): PlayerMediaInfo {
    val videoCodec = property("current-tracks/video/codec")
    val videoDecoder = property("current-tracks/video/decoder-desc")
    val dvProfile = property("current-tracks/video/dolby-vision-profile")
    val codecProfile = property("current-tracks/video/codec-profile")
    val gamma = property("video-params/gamma")
    val colorMatrix = property("video-params/colormatrix")
    val dynamicRange = mpvPlaybackHdrFormat(
        dvProfile = dvProfile,
        videoCodec = videoCodec,
        videoDecoder = videoDecoder,
        codecProfile = codecProfile,
        gamma = gamma,
        colorMatrix = colorMatrix,
    )
    return PlayerMediaInfo(
        videoCodec = videoCodec?.let(::mpvCodecLabel),
        videoWidth = property("video-params/w")?.toIntOrNull()?.takeIf { it > 0 },
        videoHeight = property("video-params/h")?.toIntOrNull()?.takeIf { it > 0 },
        videoFrameRate = property("container-fps")?.toFloatOrNull()?.takeIf { it > 0f },
        videoBitrate = property("video-bitrate")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        audioCodec = property("current-tracks/audio/codec")?.let(::mpvCodecLabel),
        audioChannels = property("audio-params/channel-count")?.toIntOrNull()?.takeIf { it > 0 },
        audioSampleRate = property("audio-params/samplerate")?.toIntOrNull()?.takeIf { it > 0 },
        audioProfiles = listOfNotNull(property("current-tracks/audio/codec-profile")),
        hdr = buildList {
            if (dynamicRange == "dolby_vision") add("DV")
            when (gamma?.trim()?.lowercase()) {
                "pq" -> add("HDR (PQ)")
                "hlg" -> add("HLG")
            }
        },
        bitDepth = property("video-params/pixelformat")?.let(::mpvBitDepth),
    )
}

internal fun mpvBitDepth(pixelFormat: String): Int? = when {
    pixelFormat.contains("12", ignoreCase = true) -> 12
    pixelFormat.contains("10", ignoreCase = true) -> 10
    else -> null
}

private fun mpvCodecLabel(codec: String): String? =
    when (val c = codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "dts" -> "DTS"
        else -> when {
            c.startsWith("pcm") -> "PCM"
            else -> codec.trim().uppercase()
        }
    }
