package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        defaultRequestHeaders: Map<String, String>,
        defaultResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        useLongReadTimeout: Boolean = false,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    ): DataSource.Factory {
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            defaultRequestHeaders,
            useLongReadTimeout,
        )
        val subtitleHeaderFactory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = httpFactory,
            externalSubtitles = externalSubtitles
        )
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, subtitleHeaderFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
    
    fun createHlsSegmentInspectionDataSourceFactory(
        defaultRequestHeaders: Map<String, String>,
        useLongReadTimeout: Boolean,
        useYoutubeChunkedPlayback: Boolean,
    ): DataSource.Factory =
        PlayerPlaybackNetworking.createHttpDataSourceFactory(
            defaultHeaders = defaultRequestHeaders,
            useLongReadTimeout = useLongReadTimeout,
            callTimeoutMs = HLS_SEGMENT_INSPECTION_CALL_TIMEOUT_MS,
        )
}
