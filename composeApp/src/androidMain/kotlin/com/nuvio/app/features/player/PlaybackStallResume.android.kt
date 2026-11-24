package com.nuvio.app.features.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import java.io.EOFException

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.toPlaybackSourceFailure(): PlaybackSourceFailure {
    // StuckPlayerException is absent from Media3 1.8; reflect to keep forward compatibility
    // without linking this source set to a class missing from the pinned Nuvio AARs.
    val stuckPlayer = generateSequence<Throwable>(this) { it.cause }
        .firstOrNull { cause ->
            val type = cause.javaClass
            if (type.name != "androidx.media3.common.util.StuckPlayerException") {
                false
            } else {
                val stuckType = runCatching { type.getField("stuckType").getInt(cause) }.getOrNull()
                val recognizedTypes = listOf(
                    "STUCK_BUFFERING_NOT_LOADING",
                    "STUCK_BUFFERING_NO_PROGRESS",
                    "STUCK_PLAYING_NO_PROGRESS",
                ).mapNotNull { fieldName ->
                    runCatching { type.getField(fieldName).getInt(null) }.getOrNull()
                }
                stuckType != null && stuckType in recognizedTypes
            }
        }
    if (stuckPlayer != null) return PlaybackSourceFailure.StuckPlayback
    if (errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
        return PlaybackSourceFailure.ReadPositionOutOfRange
    }
    val closed = errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        generateSequence<Throwable>(this) { it.cause }.any { it is EOFException }
    return if (closed) PlaybackSourceFailure.ClosedStream else PlaybackSourceFailure.None
}
