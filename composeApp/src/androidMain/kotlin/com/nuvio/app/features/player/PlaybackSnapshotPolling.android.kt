package com.nuvio.app.features.player

/**
 * Position snapshots need a 4Hz cadence during active playback for smooth UI progress.
 * While paused, player events (including seek discontinuities and timeline changes) publish
 * state immediately, so the fallback poll can run at 1Hz instead of waking four times per second.
 */
internal fun playbackSnapshotPollingIntervalMs(isPlaying: Boolean): Long =
    if (isPlaying) ACTIVE_PLAYBACK_POLL_INTERVAL_MS else IDLE_PLAYBACK_POLL_INTERVAL_MS

internal const val ACTIVE_PLAYBACK_POLL_INTERVAL_MS = 250L
internal const val IDLE_PLAYBACK_POLL_INTERVAL_MS = 1_000L
