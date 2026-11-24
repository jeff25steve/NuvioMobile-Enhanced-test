package com.nuvio.app.features.player

import android.hardware.display.DisplayManager
import android.view.Surface
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvSurfaceFrameRateTest {
    @Test
    fun seamlessPreferencesRequestExactSourceCadenceAtPlaybackSpeed() {
        val request = mpvSurfaceFrameRateRequest(
            userPreference = DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY,
            mediaFrameRate = 23.976,
            playbackSpeed = 1.25,
            durationSeconds = 7_200.0,
            isPlaying = true,
            liveStream = false,
        )

        assertEquals(29.97f, request.frameRate, 0.001f)
        assertEquals(Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, request.changeFrameRateStrategy)
    }

    @Test
    fun alwaysPreferenceUsesNonSeamlessMatchingForLongVod() {
        val request = mpvSurfaceFrameRateRequest(
            userPreference = DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS,
            mediaFrameRate = 24.0,
            playbackSpeed = 1.5,
            durationSeconds = 7_200.0,
            isPlaying = true,
            liveStream = false,
        )

        assertEquals(36f, request.frameRate)
        assertEquals(Surface.CHANGE_FRAME_RATE_ALWAYS, request.changeFrameRateStrategy)
    }

    @Test
    fun alwaysPreferenceUsesNonSeamlessMatchingForLivePlayback() {
        val request = mpvSurfaceFrameRateRequest(
            userPreference = DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS,
            mediaFrameRate = 50.0,
            playbackSpeed = 1.0,
            durationSeconds = Double.NaN,
            isPlaying = true,
            liveStream = true,
        )

        assertEquals(50f, request.frameRate)
        assertEquals(Surface.CHANGE_FRAME_RATE_ALWAYS, request.changeFrameRateStrategy)
    }

    @Test
    fun shortVodNeverRequestsNonSeamlessMode() {
        val request = mpvSurfaceFrameRateRequest(
            userPreference = DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS,
            mediaFrameRate = 24.0,
            playbackSpeed = 1.0,
            durationSeconds = 299.999,
            isPlaying = true,
            liveStream = false,
        )

        assertEquals(24f, request.frameRate)
        assertEquals(Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, request.changeFrameRateStrategy)
    }

    @Test
    fun neverPreferencePauseAndEndClearTheSurfaceRate() {
        assertEquals(
            0f,
            mpvSurfaceFrameRateRequest(
                DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER, 24.0, 1.0, 7_200.0, true, false,
            ).frameRate,
        )
        assertEquals(
            0f,
            mpvSurfaceFrameRateRequest(
                DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS, 24.0, 1.0, 7_200.0, false, false,
            ).frameRate,
        )
    }

    @Test
    fun invalidOrOverflowedMetadataClearsRatherThanRetainingAStaleRate() {
        val invalidRates = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            -1.0,
            0.0,
            Double.MAX_VALUE,
        )
        invalidRates.forEach { rate ->
            assertEquals(
                0f,
                mpvSurfaceFrameRateRequest(
                    DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS,
                    rate,
                    2.0,
                    7_200.0,
                    true,
                    false,
                ).frameRate,
            )
        }
        assertEquals(
            0f,
            mpvSurfaceFrameRateRequest(
                DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS,
                24.0,
                Double.NaN,
                7_200.0,
                true,
                false,
            ).frameRate,
        )
    }
}
