@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.hardware.display.DisplayManager
import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFrameRateMatchingTest {
    @Test
    fun neverPreferenceDisablesMedia3SurfaceFrameRateRequests() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER),
        )
    }

    @Test
    fun allowedAndUnknownPreferencesKeepSeamlessOnlyMatching() {
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY),
        )
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS),
        )
        assertEquals(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            videoFrameRateStrategyForSystemPreference(DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN),
        )
    }
}
