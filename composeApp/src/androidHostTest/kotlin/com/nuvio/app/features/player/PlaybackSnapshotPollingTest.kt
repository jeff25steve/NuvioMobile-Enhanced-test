package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSnapshotPollingTest {
    @Test
    fun activePlaybackKeepsSmoothProgressCadence() {
        assertEquals(250L, playbackSnapshotPollingIntervalMs(isPlaying = true))
    }

    @Test
    fun pausedPlaybackUsesOneQuarterOfThePollingWakeups() {
        assertEquals(1_000L, playbackSnapshotPollingIntervalMs(isPlaying = false))
    }
}
