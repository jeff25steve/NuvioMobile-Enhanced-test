package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap

internal fun seekPreviewTargetShortSidePx(maxWidthPx: Int): Int =
    (maxWidthPx * 9 / 16).coerceAtLeast(16)

internal fun seekPreviewDisplayAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()).coerceIn(1f, 2.4f)
    } else {
        16f / 9f
    }

internal interface SeekPreviewFrameSource {
    fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap?

    fun cancel() = Unit

    fun close()
}

internal expect fun openSeekPreviewFrameSource(
    url: String,
    headers: Map<String, String>,
): SeekPreviewFrameSource?

internal interface SeekPreviewFeed {
    fun want(positionMs: Long, showingMs: Long?)

    fun prepare()

    fun close()
}

internal expect fun openSeekPreviewFeed(
    url: String,
    headers: Map<String, String>,
    onThumbnail: (positionMs: Long, bitmap: ImageBitmap?, error: String?) -> Unit,
): SeekPreviewFeed?
