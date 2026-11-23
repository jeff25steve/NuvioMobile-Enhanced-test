package com.nuvio.app.features.player

/**
 * Position snapshots need a 4Hz cadence during active playback for smooth UI progress.
 * While paused, player events (including seek discontinuities and timeline changes) publish
 * state immediately, so the fallback polling loop can stop entirely.
 */
internal fun playbackSnapshotPollingIntervalMs(isPlaying: Boolean): Long? =
    if (isPlaying) ACTIVE_PLAYBACK_POLL_INTERVAL_MS else null

internal const val ACTIVE_PLAYBACK_POLL_INTERVAL_MS = 250L
