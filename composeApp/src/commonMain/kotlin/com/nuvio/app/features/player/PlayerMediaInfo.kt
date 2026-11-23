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
    val videoColorPrimaries: String? = null,
    val videoTransferFunction: String? = null,
    val videoColorMatrix: String? = null,
    val videoColorRange: String? = null,
    val videoOutputWidth: Int? = null,
    val videoOutputHeight: Int? = null,
    val videoOutputPixelFormat: String? = null,
    val videoOutputBitDepth: Int? = null,
    val videoOutputColorPrimaries: String? = null,
    val videoOutputTransferFunction: String? = null,
    val videoOutputColorMatrix: String? = null,
    val videoOutputColorRange: String? = null,
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
    val colorLevels = property("video-params/colorlevels")
    val outputPixelFormat = property("video-out-params/pixelformat")
    val outputColorLevels = property("video-out-params/colorlevels")
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
        videoColorPrimaries = property("video-params/primaries"),
        videoTransferFunction = gamma,
        videoColorMatrix = colorMatrix,
        videoColorRange = mpvPlaybackColorRange(colorLevels),
        videoOutputWidth = property("video-out-params/w")?.toIntOrNull()?.takeIf { it > 0 },
        videoOutputHeight = property("video-out-params/h")?.toIntOrNull()?.takeIf { it > 0 },
        videoOutputPixelFormat = outputPixelFormat?.takeIf { it.isNotBlank() },
        videoOutputBitDepth = outputPixelFormat?.let(::mpvBitDepth),
        videoOutputColorPrimaries = property("video-out-params/primaries"),
        videoOutputTransferFunction = property("video-out-params/gamma"),
        videoOutputColorMatrix = property("video-out-params/colormatrix"),
        videoOutputColorRange = mpvPlaybackColorRange(outputColorLevels),
    )
}

private val MPV_PIXEL_FORMAT_DEPTH_SUFFIX = Regex("""(?:p|gray|xyz)0?(8|9|10|12|14|16)(?:le|be)?$""")

private val MPV_8_BIT_PIXEL_FORMATS = setOf(
    "yuv410p", "yuv411p", "yuv420p", "yuv422p", "yuv440p", "yuv444p",
    "yuvj420p", "yuvj422p", "yuvj440p", "yuvj444p",
    "nv12", "nv21", "yuyv422", "uyvy422",
    "rgb24", "bgr24", "rgba", "bgra", "argb", "abgr",
    "rgb0", "bgr0", "0rgb", "0bgr", "gbrp", "gray", "gray8",
)

private val MPV_16_BIT_PACKED_RGB_PIXEL_FORMATS = setOf(
    "rgb48le", "rgb48be", "bgr48le", "bgr48be",
    "rgba64le", "rgba64be", "bgra64le", "bgra64be",
    "argb64le", "argb64be", "abgr64le", "abgr64be",
)

internal fun mpvBitDepth(pixelFormat: String): Int? {
    val normalized = pixelFormat.trim().lowercase()
    MPV_PIXEL_FORMAT_DEPTH_SUFFIX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }
    return when {
        normalized in MPV_8_BIT_PIXEL_FORMATS -> 8
        normalized in MPV_16_BIT_PACKED_RGB_PIXEL_FORMATS -> 16
        else -> null
    }
}

internal fun mpvPlaybackEstimatedFps(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it > 0.0 }

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
