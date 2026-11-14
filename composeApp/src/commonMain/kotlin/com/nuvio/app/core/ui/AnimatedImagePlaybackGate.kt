package com.nuvio.app.core.ui

/**
 * Reference-counts active playback surfaces so one surface cannot resume shared
 * animated-image work while another surface is still playing.
 */
internal class AnimatedImagePlaybackGate {
    private var activePlaybackCount = 0

    val isSuspended: Boolean
        get() = activePlaybackCount > 0

    /** Returns true only when this call begins the first active playback. */
    fun suspendPlayback(): Boolean {
        activePlaybackCount++
        return activePlaybackCount == 1
    }

    /** Returns true only when this call ends the last active playback. */
    fun resumePlayback(): Boolean {
        if (activePlaybackCount == 0) return false
        activePlaybackCount--
        return activePlaybackCount == 0
    }

    /** Used by the delayed resume job to discard a stale cooldown. */
    fun canFinishResumeCooldown(): Boolean = activePlaybackCount == 0
}
