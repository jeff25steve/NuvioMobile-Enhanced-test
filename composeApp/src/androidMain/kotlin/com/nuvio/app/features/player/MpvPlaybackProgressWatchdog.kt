package com.nuvio.app.features.player

/**
 * Diagnostic-only MPV media-clock watchdog.
 *
 * It detects sustained under-progress after playback has already demonstrated healthy movement.
 * It never changes player state or initiates engine recovery. Callers own lifecycle/eligibility
 * (pause, seek, EOF and source generation) and must pass monotonic elapsed-realtime timestamps.
 */
internal data class MpvPlaybackProgressStall(
    val attempt: Int,
    val elapsedMs: Long,
    val mediaProgressMs: Long,
    val expectedProgressMs: Long,
    val consecutiveSlowSamples: Int,
)

internal class MpvPlaybackProgressWatchdog(
    private val sampleIntervalMs: Long = 3_000L,
    private val minimumProgressFraction: Double = 0.15,
    private val healthyWarmupSamples: Int = 5,
    private val slowSamplesToReport: Int = 5,
) {
    private var attempt = 0
    private var previousAtMs: Long? = null
    private var previousPositionMs: Long? = null
    private var establishedSamples = 0
    private var consecutiveSlowSamples = 0
    private var reported = false
    private var startedAtMs: Long? = null

    init {
        require(sampleIntervalMs > 0L)
        require(minimumProgressFraction in 0.0..1.0)
        require(healthyWarmupSamples > 0)
        require(slowSamplesToReport > 0)
    }

    @Synchronized
    fun beginAttempt(attempt: Int) {
        this.attempt = attempt
        previousAtMs = null
        previousPositionMs = null
        establishedSamples = 0
        consecutiveSlowSamples = 0
        reported = false
        startedAtMs = null
    }

    /** Marks a playback-restart boundary; the next sample establishes a fresh baseline. */
    @Synchronized
    fun rebaseline() {
        previousAtMs = null
        previousPositionMs = null
        consecutiveSlowSamples = 0
    }

    /**
     * Samples media-clock movement. Ineligible periods rebaseline without erasing a previously
     * established healthy session; source changes must use [beginAttempt].
     */
    @Synchronized
    fun sample(
        attempt: Int,
        nowMs: Long,
        positionMs: Long,
        playbackSpeed: Float,
        eligible: Boolean,
    ): MpvPlaybackProgressStall? {
        if (attempt != this.attempt || nowMs < 0L || positionMs < 0L) return null
        if (!eligible || !playbackSpeed.isFinite() || playbackSpeed <= 0f) {
            rebaseline()
            return null
        }

        val lastAt = previousAtMs
        val lastPosition = previousPositionMs
        if (lastAt == null || lastPosition == null || nowMs <= lastAt) {
            previousAtMs = nowMs
            previousPositionMs = positionMs
            if (startedAtMs == null) startedAtMs = nowMs
            return null
        }

        val elapsed = nowMs - lastAt
        if (elapsed < sampleIntervalMs) return null

        val progress = (positionMs - lastPosition).coerceAtLeast(0L)
        val expected = (elapsed.toDouble() * playbackSpeed.toDouble()).toLong().coerceAtLeast(1L)
        val minimum = (expected * minimumProgressFraction).toLong()
        previousAtMs = nowMs
        previousPositionMs = positionMs

        if (progress >= minimum) {
            establishedSamples = (establishedSamples + 1).coerceAtMost(healthyWarmupSamples)
            consecutiveSlowSamples = 0
            return null
        }

        if (establishedSamples < healthyWarmupSamples) {
            // Intermittent startup/network progress must not permanently poison warm-up.
            establishedSamples = (establishedSamples - 1).coerceAtLeast(0)
            return null
        }

        consecutiveSlowSamples += 1
        if (reported || consecutiveSlowSamples < slowSamplesToReport) return null
        reported = true
        return MpvPlaybackProgressStall(
            attempt = this.attempt,
            elapsedMs = (nowMs - (startedAtMs ?: nowMs)).coerceAtLeast(0L),
            mediaProgressMs = progress,
            expectedProgressMs = expected,
            consecutiveSlowSamples = consecutiveSlowSamples,
        )
    }
}
