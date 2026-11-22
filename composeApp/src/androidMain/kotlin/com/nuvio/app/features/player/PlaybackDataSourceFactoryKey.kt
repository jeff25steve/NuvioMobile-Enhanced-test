package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamSubtitle
import java.util.concurrent.atomic.AtomicLong

/**
 * Every value captured by the Android playback DataSource factory.
 *
 * Keep this key complete: source/audio URL, stream type and the byte counter all change
 * independently across playback attempts and must invalidate the remembered factory.
 */
internal data class PlaybackDataSourceFactoryKey(
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val streamType: String?,
    val useYoutubeChunkedPlayback: Boolean,
    val externalSubtitles: List<StreamSubtitle>,
    val liveStream: Boolean,
    val networkBytesRead: AtomicLong,
)
