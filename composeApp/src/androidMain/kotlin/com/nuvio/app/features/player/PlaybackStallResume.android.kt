package com.nuvio.app.features.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import java.io.EOFException

internal fun PlaybackException.toPlaybackSourceFailure(): PlaybackSourceFailure {
    val stuckPlayer = generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<StuckPlayerException>()
        .firstOrNull()
    if (stuckPlayer != null) {
        return when (stuckPlayer.stuckType) {
            StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
            StuckPlayerException.STUCK_PLAYING_NO_PROGRESS -> PlaybackSourceFailure.StuckPlayback
            else -> PlaybackSourceFailure.None
        }
    }
    if (errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
        return PlaybackSourceFailure.ReadPositionOutOfRange
    }
    val closed = errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        generateSequence<Throwable>(this) { it.cause }.any { it is EOFException }
    return if (closed) PlaybackSourceFailure.ClosedStream else PlaybackSourceFailure.None
}
