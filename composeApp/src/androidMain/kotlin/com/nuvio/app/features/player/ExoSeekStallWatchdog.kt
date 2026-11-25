package com.nuvio.app.features.player

import androidx.media3.common.Player

/**
 * Diagnostic-only detector for a seek that remains in active buffering.
 *
 * This deliberately does not infer a decoder failure from buffering and never changes player
 * state. Pauses, playback suppression and a return to READY stop the pending measurement.
 */
internal data class ExoSeekStallReport(
    val fromPositionMs: Long,
    val targetPositionMs: Long,
    val activeBufferingMs: Long,
    val seekElapsedMs: Long,
)

internal class ExoSeekStallWatchdog {
    private var currentAttempt: Int = 0
    private var fromPositionMs: Long? = null
    private var targetPositionMs: Long? = null
    private var seekStartedAtMs: Long? = null
    private var activeBufferingStartedAtMs: Long? = null
    private var reported = false

    @Synchronized
    fun beginAttempt(attempt: Int) {
        currentAttempt = attempt
        clearPendingSeek()
    }

    @Synchronized
    fun recordSeek(
        attempt: Int,
        startedAtMs: Long,
        fromPositionMs: Long,
        targetPositionMs: Long,
    ): Boolean {
        if (attempt != currentAttempt || startedAtMs < 0L ||
            fromPositionMs < 0L || targetPositionMs < 0L
        ) return false

        this.fromPositionMs = fromPositionMs
        this.targetPositionMs = targetPositionMs
        seekStartedAtMs = startedAtMs
        activeBufferingStartedAtMs = null
        reported = false
        return true
    }

    /**
     * Returns one report after [timeoutMs] of uninterrupted, unsuppressed buffering following
     * a seek. READY/IDLE/ENDED completes or abandons the observation; paused/suppressed time
     * is excluded rather than counted as a seek stall.
     */
    @Synchronized
    fun poll(
        attempt: Int,
        nowMs: Long,
        playbackState: Int,
        playWhenReady: Boolean,
        isPlaybackSuppressed: Boolean,
        currentPositionMs: Long,
        timeoutMs: Long,
    ): ExoSeekStallReport? {
        if (attempt != currentAttempt || fromPositionMs == null ||
            targetPositionMs == null || reported || nowMs < 0L ||
            currentPositionMs < 0L || timeoutMs <= 0L
        ) return null

        if (playbackState == Player.STATE_READY ||
            playbackState == Player.STATE_IDLE ||
            playbackState == Player.STATE_ENDED
        ) {
            clearPendingSeek()
            return null
        }

        if (!playWhenReady || isPlaybackSuppressed || playbackState != Player.STATE_BUFFERING) {
            activeBufferingStartedAtMs = null
            return null
        }

        val startedAtMs = activeBufferingStartedAtMs
        if (startedAtMs == null || nowMs < startedAtMs) {
            activeBufferingStartedAtMs = nowMs
            return null
        }

        val elapsedMs = nowMs - startedAtMs
        if (elapsedMs < timeoutMs) return null

        reported = true
        return ExoSeekStallReport(
            fromPositionMs = requireNotNull(fromPositionMs),
            targetPositionMs = requireNotNull(targetPositionMs),
            activeBufferingMs = elapsedMs,
            seekElapsedMs = (nowMs - requireNotNull(seekStartedAtMs)).coerceAtLeast(0L),
        )
    }

    private fun clearPendingSeek() {
        fromPositionMs = null
        targetPositionMs = null
        seekStartedAtMs = null
        activeBufferingStartedAtMs = null
        reported = false
    }
}
