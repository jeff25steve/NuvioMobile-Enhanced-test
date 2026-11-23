package com.nuvio.app.features.player.aiostreams

import androidx.media3.common.VideoSize

internal data class AssVideoDimensions(
    val width: Int,
    val height: Int,
)

/** Returns only usable source dimensions; UNKNOWN must not reuse the previous video's layout. */
internal fun assVideoDimensionsFromSize(videoSize: VideoSize): AssVideoDimensions? =
    if (videoSize.width > 0 && videoSize.height > 0) {
        AssVideoDimensions(videoSize.width, videoSize.height)
    } else {
        null
    }

/** ASS cues need a valid source-video coordinate space before a track can be shown. */
internal fun hasUsableAssVideoGeometry(videoWidth: Int, videoHeight: Int): Boolean =
    videoWidth > 0 && videoHeight > 0
