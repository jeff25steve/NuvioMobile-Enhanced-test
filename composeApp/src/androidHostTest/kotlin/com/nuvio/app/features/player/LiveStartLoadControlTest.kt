@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LiveStartLoadControlTest {
    @Test
    fun allocatorLookupIsForwardedWithThePlayerIdentity() {
        val delegate = DefaultLoadControl.Builder().build()
        val wrapper = LiveStartLoadControl(delegate)

        assertSame(
            delegate.getAllocator(PlayerId.UNSET),
            wrapper.getAllocator(PlayerId.UNSET),
        )
    }

    @Test
    fun preloadingDecisionIsForwardedWithThePlayerIdentity() {
        val delegate = DefaultLoadControl.Builder().build()
        val wrapper = LiveStartLoadControl(delegate)
        val playerId = PlayerId.PRELOAD
        val timeline = Timeline.EMPTY
        val periodId = MediaSource.MediaPeriodId("preload-test")

        assertEquals(
            delegate.shouldContinuePreloading(playerId, timeline, periodId, 0L),
            wrapper.shouldContinuePreloading(playerId, timeline, periodId, 0L),
        )
    }
}
