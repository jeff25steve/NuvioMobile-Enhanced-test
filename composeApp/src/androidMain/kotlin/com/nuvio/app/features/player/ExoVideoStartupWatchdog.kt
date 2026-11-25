package com.nuvio.app.features.player

import androidx.media3.common.Player

/**
 * Per-prepare watchdog for a selected video track that becomes actively playable but never
 * produces its first rendered frame. This is diagnostic-only: it never changes engine or
 * decoder selection.
 */
internal class ExoVideoStartupWatchdog {
    private var currentAttempt: Int = 0
    private var activePlaybackStartedAtMs: Long? = null
    private var firstFrameRendered = false
    private var reportedAttempt: Int? = null

    @Synchronized
    fun beginAttempt(attempt: Int) {
        currentAttempt = attempt
        activePlaybackStartedAtMs = null
        firstFrameRendered = false
        reportedAttempt = null
    }

    @Synchronized
    fun markFirstFrame(attempt: Int) {
        if (attempt != currentAttempt) return
        firstFrameRendered = true
        activePlaybackStartedAtMs = null
    }

    /**
     * Starts measuring only while a video track is selected, playback is READY and ExoPlayer
     * reports active playback. Pauses, rebuffering and audio-only selections do not consume the
     * timeout window. Returns true once per attempt after [timeoutMs] of uninterrupted playback.
     */
    @Synchronized
    fun shouldReportNoFirstFrame(
        attempt: Int,
        nowMs: Long,
        isPlaying: Boolean,
        playbackState: Int,
        hasSelectedVideoTrack: Boolean,
        timeoutMs: Long,
    ): Boolean {
        if (
            attempt != currentAttempt ||
            firstFrameRendered ||
            reportedAttempt == attempt ||
            timeoutMs <= 0L
        ) return false

        if (
            !isPlaying ||
            playbackState != Player.STATE_READY ||
            !hasSelectedVideoTrack ||
            nowMs < 0L
        ) {
            activePlaybackStartedAtMs = null
            return false
        }

        val startedAtMs = activePlaybackStartedAtMs
        if (startedAtMs == null || nowMs < startedAtMs) {
            activePlaybackStartedAtMs = nowMs
            return false
        }

        if (nowMs - startedAtMs < timeoutMs) return false

        reportedAttempt = attempt
        return true
    }
}
