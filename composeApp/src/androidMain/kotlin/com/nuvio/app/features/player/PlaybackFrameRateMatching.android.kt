@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Respect Android's explicit "never" preference. For all other preferences keep Media3's
 * seamless-only strategy: Media3 1.11.1 exposes no non-seamless strategy constant, and forcing
 * CHANGE_FRAME_RATE_ALWAYS requires an app-managed Surface frame-rate lifecycle.
 */
internal fun videoFrameRateStrategyForSystemPreference(userPreference: Int): Int =
    if (userPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    } else {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    }

internal fun Context.videoFrameRateStrategyForCurrentUserPreference(): Int {
    val preference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(DisplayManager::class.java)
            ?.matchContentFrameRateUserPreference
            ?: DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
    } else {
        DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
    }
    return videoFrameRateStrategyForSystemPreference(preference)
}

internal fun ExoPlayer.applySystemVideoFrameRatePreference(context: Context) {
    setVideoChangeFrameRateStrategy(context.videoFrameRateStrategyForCurrentUserPreference())
}
