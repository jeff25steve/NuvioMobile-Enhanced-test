package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSnapshotPollingTest {
    @Test
    fun activePlaybackKeepsSmoothProgressCadence() {
        assertEquals(250L, playbackSnapshotPollingIntervalMs(isPlaying = true))
    }

    @Test
    fun pausedPlaybackStopsThePositionPollingLoop() {
        assertNull(playbackSnapshotPollingIntervalMs(isPlaying = false))
    }
}
