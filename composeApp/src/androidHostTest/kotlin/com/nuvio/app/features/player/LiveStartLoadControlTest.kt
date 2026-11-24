@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveStartLoadControlTest {
    @Test
    fun allocatorIsDelegatedWithoutChangingTheConfiguredAllocator() {
        val delegate = DefaultLoadControl()
        val wrapper = LiveStartLoadControl(delegate)

        assertSame(delegate.allocator, wrapper.allocator)
    }

    @Test
    fun preloadingDecisionIsDelegatedWithTheMedia3EighteenSignature() {
        val timeline = Timeline.EMPTY
        val mediaPeriodId = MediaSource.MediaPeriodId("preload-test")
        val delegate = RecordingLoadControl()
        val wrapper = LiveStartLoadControl(delegate)

        assertTrue(wrapper.shouldContinuePreloading(timeline, mediaPeriodId, 2_500L))
        assertSame(timeline, delegate.preloadingTimeline)
        assertSame(mediaPeriodId, delegate.preloadingMediaPeriodId)
        assertEquals(2_500L, delegate.preloadingBufferedDurationUs)
    }

    private class RecordingLoadControl : DefaultLoadControl() {
        var preloadingTimeline: Timeline? = null
            private set
        var preloadingMediaPeriodId: MediaSource.MediaPeriodId? = null
            private set
        var preloadingBufferedDurationUs: Long? = null
            private set

        override fun shouldContinuePreloading(
            timeline: Timeline,
            mediaPeriodId: MediaSource.MediaPeriodId,
            bufferedDurationUs: Long,
        ): Boolean {
            preloadingTimeline = timeline
            preloadingMediaPeriodId = mediaPeriodId
            preloadingBufferedDurationUs = bufferedDurationUs
            return true
        }
    }
}
