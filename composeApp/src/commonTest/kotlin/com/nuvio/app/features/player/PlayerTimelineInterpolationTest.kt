package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTimelineInterpolationTest {
    @Test
    fun frameInterpolationRequiresActivePlaybackAndUsableTimeline() {
        assertTrue(
            shouldAnimatePlaybackTimeline(
                isPlaying = true,
                isInteracting = false,
                durationMs = 60_000L,
                playbackSpeed = 1f,
            ),
        )
        assertFalse(shouldAnimatePlaybackTimeline(false, false, 60_000L, 1f))
        assertFalse(shouldAnimatePlaybackTimeline(true, true, 60_000L, 1f))
        assertFalse(shouldAnimatePlaybackTimeline(true, false, 0L, 1f))
        assertFalse(shouldAnimatePlaybackTimeline(true, false, 60_000L, 0f))
        assertFalse(shouldAnimatePlaybackTimeline(true, false, 60_000L, Float.NaN))
    }

    @Test
    fun advancesAtNormalPlaybackSpeed() {
        assertEquals(
            11_000L,
            interpolatePlaybackPositionMs(
                positionMs = 10_000L,
                elapsedNanos = 1_000_000_000L,
                playbackSpeed = 1f,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun scalesProgressByPlaybackSpeed() {
        assertEquals(
            13_000L,
            interpolatePlaybackPositionMs(
                positionMs = 10_000L,
                elapsedNanos = 2_000_000_000L,
                playbackSpeed = 1.5f,
                durationMs = 60_000L,
            ),
        )
        assertEquals(
            11_000L,
            interpolatePlaybackPositionMs(
                positionMs = 10_000L,
                elapsedNanos = 2_000_000_000L,
                playbackSpeed = 0.5f,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun doesNotAdvanceForInvalidPlaybackSpeedOrElapsedTime() {
        val expected = 10_000L
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { speed ->
            assertEquals(
                expected,
                interpolatePlaybackPositionMs(
                    positionMs = expected,
                    elapsedNanos = 1_000_000_000L,
                    playbackSpeed = speed,
                    durationMs = 60_000L,
                ),
            )
        }
        assertEquals(
            expected,
            interpolatePlaybackPositionMs(
                positionMs = expected,
                elapsedNanos = -1L,
                playbackSpeed = 1f,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun clampsToKnownDurationButLeavesUnknownDurationUnbounded() {
        assertEquals(
            60_000L,
            interpolatePlaybackPositionMs(
                positionMs = 59_000L,
                elapsedNanos = 2_000_000_000L,
                playbackSpeed = 1f,
                durationMs = 60_000L,
            ),
        )
        assertEquals(
            61_000L,
            interpolatePlaybackPositionMs(
                positionMs = 59_000L,
                elapsedNanos = 2_000_000_000L,
                playbackSpeed = 1f,
                durationMs = 0L,
            ),
        )
        assertEquals(
            61_000L,
            interpolatePlaybackPositionMs(
                positionMs = 59_000L,
                elapsedNanos = 2_000_000_000L,
                playbackSpeed = 1f,
                durationMs = -1L,
            ),
        )
    }

    @Test
    fun clampsNegativePositionAndSaturatesLongOverflow() {
        assertEquals(
            1_000L,
            interpolatePlaybackPositionMs(
                positionMs = -500L,
                elapsedNanos = 1_000_000_000L,
                playbackSpeed = 1f,
                durationMs = 0L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            interpolatePlaybackPositionMs(
                positionMs = Long.MAX_VALUE - 10L,
                elapsedNanos = Long.MAX_VALUE,
                playbackSpeed = Float.MAX_VALUE,
                durationMs = 0L,
            ),
        )
    }
}
