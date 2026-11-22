@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlin.test.Test
import kotlin.test.assertSame

class LiveStartLoadControlTest {
    @Test
    fun allocatorLookupIsForwardedWithThePlayerIdentity() {
        val delegate = RecordingLoadControl()
        val wrapper = LiveStartLoadControl(delegate)
        val playerId = PlayerId("live-player")

        wrapper.getAllocator(playerId)

        assertSame(playerId, delegate.allocatorPlayerId)
    }

    @Test
    fun preloadingDecisionIsForwardedWithThePlayerIdentity() {
        val delegate = RecordingLoadControl()
        val wrapper = LiveStartLoadControl(delegate)
        val playerId = PlayerId("preload-player")

        wrapper.shouldContinuePreloading(
            playerId,
            Timeline.EMPTY,
            MediaSource.MediaPeriodId("preload-test"),
            0L,
        )

        assertSame(playerId, delegate.preloadingPlayerId)
    }

    private class RecordingLoadControl : DefaultLoadControl() {
        var allocatorPlayerId: PlayerId? = null
            private set
        var preloadingPlayerId: PlayerId? = null
            private set

        override fun getAllocator(playerId: PlayerId): Allocator {
            allocatorPlayerId = playerId
            return super.getAllocator(playerId)
        }

        override fun shouldContinuePreloading(
            playerId: PlayerId,
            timeline: Timeline,
            mediaPeriodId: MediaSource.MediaPeriodId,
            bufferedDurationUs: Long,
        ): Boolean {
            preloadingPlayerId = playerId
            return true
        }
    }
}
