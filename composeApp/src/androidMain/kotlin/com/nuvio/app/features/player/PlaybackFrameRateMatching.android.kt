@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal fun videoFrameRateStrategyForSystemPreference(userPreference: Int): Int =
    if (userPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    } else {
        // ALWAYS remains seamless-only until the manual Surface request is eligible.
        // PlayerEngine disables this strategy only while CHANGE_FRAME_RATE_ALWAYS is active.
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    }

internal fun shouldUseAlwaysSurfaceFrameRateMatching(userPreference: Int): Boolean =
    userPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS

/**
 * Resolves the frame rate requested from the display for the current playback speed.
 *
 * Media3's VideoFrameReleaseHelper uses mediaFrameRate * playbackSpeed for the Surface request.
 * Return zero for unknown or invalid inputs so the caller clears any stale manual request.
 */
internal fun playbackSurfaceFrameRate(mediaFrameRate: Float, playbackSpeed: Float): Float {
    if (!mediaFrameRate.isFinite() || mediaFrameRate <= 0f) return 0f
    if (!playbackSpeed.isFinite() || playbackSpeed <= 0f) return 0f
    return (mediaFrameRate * playbackSpeed).takeIf { it.isFinite() && it > 0f } ?: 0f
}

private const val MIN_NON_SEAMLESS_MATCH_DURATION_MS = 300_000L

internal fun shouldApplyAlwaysVideoFrameRate(
    playWhenReady: Boolean,
    playbackState: Int,
    durationMs: Long,
    liveStream: Boolean,
): Boolean =
    playWhenReady &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED &&
        (liveStream || durationMs >= MIN_NON_SEAMLESS_MATCH_DURATION_MS)

internal fun Context.videoFrameRateUserPreference(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(DisplayManager::class.java)
            ?.matchContentFrameRateUserPreference
            ?: DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
    } else {
        DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
    }

internal fun Context.videoFrameRateStrategyForCurrentUserPreference(): Int =
    videoFrameRateStrategyForSystemPreference(videoFrameRateUserPreference())

internal fun ExoPlayer.applySystemVideoFrameRatePreference(context: Context) {
    setVideoChangeFrameRateStrategy(context.videoFrameRateStrategyForCurrentUserPreference())
}

/**
 * Applies Android's explicit non-seamless "Always" preference to the displayed SurfaceView.
 *
 * The Surface request is retained through buffering, but cleared while paused, ended, idle or
 * while a short VOD clip is playing. Live streams are long-running and remain eligible.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class AlwaysVideoFrameRateController(
    private val surfaceView: SurfaceView,
) : SurfaceHolder.Callback {
    private var activeSurface: Surface? = null
    private var requestedFrameRate = 0f
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var durationMs = C.TIME_UNSET
    private var liveStream = false
    private var lastAppliedFrameRate: Float? = null

    init {
        surfaceView.holder.addCallback(this)
        if (surfaceView.holder.surface.isValid) {
            activeSurface = surfaceView.holder.surface
            applyFrameRate()
        }
    }

    val hasRequestedFrameRate: Boolean
        get() = requestedFrameRate > 0f

    fun updateVideoFrameRate(frameRate: Float, playbackSpeed: Float) {
        // PlayerEngine first disables Media3's seamless request when manual Always mode
        // becomes eligible, then calls updatePlaybackState() to apply this stored rate.
        requestedFrameRate = playbackSurfaceFrameRate(frameRate, playbackSpeed)
    }

    fun updatePlaybackState(
        playWhenReady: Boolean,
        playbackState: Int,
        durationMs: Long,
        liveStream: Boolean,
    ) {
        this.playWhenReady = playWhenReady
        this.playbackState = playbackState
        this.durationMs = durationMs
        this.liveStream = liveStream
        applyFrameRate()
    }

    fun release() {
        surfaceView.holder.removeCallback(this)
        activeSurface?.let { surface ->
            if (surface.isValid) {
                surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            }
        }
        activeSurface = null
        lastAppliedFrameRate = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        activeSurface = holder.surface
        lastAppliedFrameRate = null
        applyFrameRate()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        activeSurface = holder.surface
        lastAppliedFrameRate = null
        applyFrameRate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        activeSurface = null
        lastAppliedFrameRate = null
    }

    private fun applyFrameRate() {
        val surface = activeSurface ?: return
        if (!surface.isValid) return
        val targetFrameRate = if (
            requestedFrameRate > 0f &&
            shouldApplyAlwaysVideoFrameRate(playWhenReady, playbackState, durationMs, liveStream)
        ) {
            requestedFrameRate
        } else {
            0f
        }
        if (lastAppliedFrameRate == targetFrameRate) return
        surface.setFrameRate(
            targetFrameRate,
            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            Surface.CHANGE_FRAME_RATE_ALWAYS,
        )
        lastAppliedFrameRate = targetFrameRate
    }

}
