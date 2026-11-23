package com.nuvio.app.features.player

import android.graphics.RectF
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import kotlin.math.abs

/**
 * Returns the visible video rectangle in normalized view coordinates.
 *
 * The 1% tolerance matches Media3's AspectRatioFrameLayout: below that mismatch it leaves
 * the measured view dimensions unchanged. FILL deliberately ignores the source aspect ratio.
 */
internal fun videoDisplayBoundsFraction(
    viewWidthPx: Float,
    viewHeightPx: Float,
    videoAspectRatio: Float,
    resizeMode: PlayerResizeMode,
): RectF? {
    if (
        !viewWidthPx.isFinite() || !viewHeightPx.isFinite() ||
        viewWidthPx <= 0f || viewHeightPx <= 0f
    ) {
        return null
    }
    if (resizeMode == PlayerResizeMode.Fill) return RectF(0f, 0f, 1f, 1f)
    if (!videoAspectRatio.isFinite() || videoAspectRatio <= 0f) return null

    val viewAspectRatio = viewWidthPx / viewHeightPx
    if (!viewAspectRatio.isFinite() || viewAspectRatio <= 0f) return null
    val aspectDeformation = videoAspectRatio / viewAspectRatio - 1f
    if (!aspectDeformation.isFinite()) return null
    if (abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
        return RectF(0f, 0f, 1f, 1f)
    }

    val widthRatio = videoAspectRatio / viewAspectRatio
    val heightRatio = viewAspectRatio / videoAspectRatio
    val displayedWidth = when (resizeMode) {
        PlayerResizeMode.Fit -> minOf(1f, widthRatio)
        PlayerResizeMode.Zoom -> maxOf(1f, widthRatio)
        PlayerResizeMode.Fill -> 1f
    }
    val displayedHeight = when (resizeMode) {
        PlayerResizeMode.Fit -> minOf(1f, heightRatio)
        PlayerResizeMode.Zoom -> maxOf(1f, heightRatio)
        PlayerResizeMode.Fill -> 1f
    }
    if (!displayedWidth.isFinite() || !displayedHeight.isFinite()) return null

    val left = (1f - displayedWidth) / 2f
    val top = (1f - displayedHeight) / 2f
    return RectF(left, top, left + displayedWidth, top + displayedHeight)
}

/** Returns the actual video height used to normalize PiP subtitle text size. */
internal fun displayedVideoHeightPx(
    viewWidthPx: Float,
    viewHeightPx: Float,
    videoAspectRatio: Float,
    resizeMode: PlayerResizeMode,
): Float? {
    if (
        !viewWidthPx.isFinite() || !viewHeightPx.isFinite() ||
        viewWidthPx <= 0f || viewHeightPx <= 0f
    ) {
        return null
    }
    return when (resizeMode) {
        PlayerResizeMode.Fit -> {
            if (!videoAspectRatio.isFinite() || videoAspectRatio <= 0f) return null
            minOf(viewHeightPx, viewWidthPx / videoAspectRatio)
        }
        PlayerResizeMode.Fill,
        PlayerResizeMode.Zoom -> viewHeightPx
    }.takeIf { it.isFinite() && it > 0f }
}

internal fun displayedVideoHeightPx(
    viewSize: IntSize,
    videoSize: VideoSize,
    resizeMode: PlayerResizeMode,
): Float? {
    if (viewSize.width <= 0 || viewSize.height <= 0 || videoSize.width <= 0 || videoSize.height <= 0) {
        return null
    }
    val videoAspectRatio = (
        videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio
    ) / videoSize.height.toFloat()
    return displayedVideoHeightPx(
        viewWidthPx = viewSize.width.toFloat(),
        viewHeightPx = viewSize.height.toFloat(),
        videoAspectRatio = videoAspectRatio,
        resizeMode = resizeMode,
    )
}


/** Maps bitmap subtitle coordinates from source-video space into the displayed video rectangle. */
internal fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
    val builder = cue.buildUpon()
    if (cue.position != Cue.DIMEN_UNSET) {
        builder.setPosition(bounds.left + cue.position * bounds.width())
    }
    if (cue.size != Cue.DIMEN_UNSET) {
        builder.setSize(cue.size * bounds.width())
    }
    if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
        builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
    }
    if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
        builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
    }
    return builder.build()
}

private const val MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f
