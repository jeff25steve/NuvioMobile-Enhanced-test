package com.nuvio.app.features.player

import android.hardware.display.DisplayManager
import android.view.Surface

internal data class MpvSurfaceFrameRateRequest(
    val frameRate: Float,
    val changeFrameRateStrategy: Int,
)

/**
 * Mirrors Android's ExoPlayer frame-rate policy for the libmpv SurfaceControl layer.
 *
 * Only the user's explicit Always preference opts into a non-seamless switch, and only for
 * long-running VOD or live playback. Other preferences remain seamless-only.
 */
internal fun mpvSurfaceFrameRateRequest(
    userPreference: Int,
    mediaFrameRate: Double,
    playbackSpeed: Double,
    durationSeconds: Double,
    isPlaying: Boolean,
    liveStream: Boolean,
): MpvSurfaceFrameRateRequest {
    val seamlessOnly = Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
    if (
        userPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER ||
        !isPlaying ||
        !mediaFrameRate.isFinite() ||
        mediaFrameRate <= 0.0 ||
        !playbackSpeed.isFinite() ||
        playbackSpeed <= 0.0
    ) {
        return MpvSurfaceFrameRateRequest(0f, seamlessOnly)
    }

    val requestedRate = mediaFrameRate * playbackSpeed
    if (!requestedRate.isFinite() || requestedRate <= 0.0 || requestedRate > Float.MAX_VALUE) {
        return MpvSurfaceFrameRateRequest(0f, seamlessOnly)
    }

    val longRunningContent = liveStream || (durationSeconds.isFinite() && durationSeconds >= 300.0)
    val alwaysEligible = userPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS &&
        longRunningContent

    return MpvSurfaceFrameRateRequest(
        frameRate = requestedRate.toFloat(),
        changeFrameRateStrategy = if (alwaysEligible) {
            Surface.CHANGE_FRAME_RATE_ALWAYS
        } else {
            seamlessOnly
        },
    )
}
