@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.hardware.display.DisplayManager
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFrameRateMatchingTest {
    @Test
    fun neverPreferenceDisablesMedia3SurfaceFrameRateRequests() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER),
        )
        assertFalse(shouldUseAlwaysSurfaceFrameRateMatching(DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER))
    }

    @Test
    fun alwaysPreferenceKeepsSeamlessMatchingUntilManualSurfaceModeIsEligible() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS),
        )
        assertTrue(shouldUseAlwaysSurfaceFrameRateMatching(DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS))
    }

    @Test
    fun seamlessOnlyAndUnknownPreferencesKeepMedia3SeamlessMatching() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY),
        )
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN),
        )
    }

    @Test
    fun manualSurfaceFrameRateIncludesPlaybackSpeed() {
        assertEquals(36f, playbackSurfaceFrameRate(24f, 1.5f))
        assertEquals(29.97f, playbackSurfaceFrameRate(23.976f, 1.25f), 0.001f)
        assertEquals(30f, playbackSurfaceFrameRate(60f, 0.5f))
    }

    @Test
    fun invalidMediaFrameRateOrPlaybackSpeedClearsManualSurfaceRequest() {
        assertEquals(0f, playbackSurfaceFrameRate(Float.NaN, 1f))
        assertEquals(0f, playbackSurfaceFrameRate(-1f, 1f))
        assertEquals(0f, playbackSurfaceFrameRate(24f, 0f))
        assertEquals(0f, playbackSurfaceFrameRate(24f, Float.NaN))
        assertEquals(0f, playbackSurfaceFrameRate(Float.MAX_VALUE, Float.MAX_VALUE))
    }

    @Test
    fun nonSeamlessMatchingIsLimitedToLongVODOrLivePlaybackThatIsIntendedToRun() {
        assertFalse(shouldApplyAlwaysVideoFrameRate(
            playWhenReady = false,
            playbackState = Player.STATE_READY,
            durationMs = 600_000L,
            liveStream = false,
        ))
        assertFalse(shouldApplyAlwaysVideoFrameRate(
            playWhenReady = true,
            playbackState = Player.STATE_ENDED,
            durationMs = 600_000L,
            liveStream = false,
        ))
        assertFalse(shouldApplyAlwaysVideoFrameRate(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            durationMs = 299_999L,
            liveStream = false,
        ))
        assertTrue(shouldApplyAlwaysVideoFrameRate(
            playWhenReady = true,
            playbackState = Player.STATE_BUFFERING,
            durationMs = 300_000L,
            liveStream = false,
        ))
        assertTrue(shouldApplyAlwaysVideoFrameRate(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            durationMs = C.TIME_UNSET,
            liveStream = true,
        ))
    }
}
