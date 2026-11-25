package com.nuvio.app.features.player

import androidx.media3.common.Player

/**
 * Diagnostic-only detector for a selected video stream whose frame-output callbacks stop while
 * the player clock continues advancing. A known nominal frame rate is required so image-like or
 * low-frame-rate tracks are not mistaken for a frozen video stream.
 */
internal class VideoFrameOutputGapWatchdog {
    private var currentAttempt: Int = 0
    private var lastFramePresentationTimeUs: Long? = null
    private var lastKnownFrameRate: Float = Float.NaN
    private var activeGapStartedAtMs: Long? = null
    private var activeGapStartPositionMs: Long? = null
    private var reportedGap = false

    @Synchronized
    fun beginAttempt(attempt: Int) {
        currentAttempt = attempt
        lastFramePresentationTimeUs = null
        lastKnownFrameRate = Float.NaN
        activeGapStartedAtMs = null
        activeGapStartPositionMs = null
        reportedGap = false
    }

    @Synchronized
    fun recordFrame(
        attempt: Int,
        elapsedRealtimeMs: Long,
        playbackPositionMs: Long,
        presentationTimeUs: Long,
        frameRate: Float,
    ) {
        if (
            attempt != currentAttempt ||
            elapsedRealtimeMs < 0L ||
            playbackPositionMs < 0L ||
            presentationTimeUs < 0L
        ) return

        lastFramePresentationTimeUs = presentationTimeUs
        lastKnownFrameRate = frameRate
        activeGapStartedAtMs = elapsedRealtimeMs
        activeGapStartPositionMs = playbackPositionMs
        reportedGap = false
    }

    /**
     * Requires a known frame rate of at least [MIN_DIAGNOSTIC_FRAME_RATE] and measurable player
     * position progress. This deliberately reports a frame-output gap, not proof that the physical
     * display is frozen. It never changes playback or decoder selection.
     */
    @Synchronized
    fun shouldReportGap(
        attempt: Int,
        nowMs: Long,
        playbackPositionMs: Long,
        isPlaying: Boolean,
        playbackState: Int,
        hasSelectedVideoTrack: Boolean,
        timeoutMs: Long,
        minimumPositionProgressMs: Long,
    ): Boolean {
        if (
            attempt != currentAttempt ||
            reportedGap ||
            lastFramePresentationTimeUs == null ||
            lastKnownFrameRate < MIN_DIAGNOSTIC_FRAME_RATE ||
            !lastKnownFrameRate.isFinite() ||
            timeoutMs <= 0L ||
            minimumPositionProgressMs <= 0L
        ) return false

        if (
            !isPlaying ||
            playbackState != Player.STATE_READY ||
            !hasSelectedVideoTrack ||
            nowMs < 0L ||
            playbackPositionMs < 0L
        ) {
            activeGapStartedAtMs = null
            activeGapStartPositionMs = null
            return false
        }

        val startedAtMs = activeGapStartedAtMs
        val startPositionMs = activeGapStartPositionMs
        if (startedAtMs == null || startPositionMs == null || nowMs < startedAtMs ||
            playbackPositionMs < startPositionMs
        ) {
            activeGapStartedAtMs = nowMs
            activeGapStartPositionMs = playbackPositionMs
            return false
        }

        if (nowMs - startedAtMs < timeoutMs) return false
        if (playbackPositionMs - startPositionMs < minimumPositionProgressMs) return false

        reportedGap = true
        return true
    }

    @Synchronized
    fun lastFramePresentationTimeUs(attempt: Int): Long? =
        lastFramePresentationTimeUs.takeIf { attempt == currentAttempt }

    private companion object {
        const val MIN_DIAGNOSTIC_FRAME_RATE = 5f
    }
}
