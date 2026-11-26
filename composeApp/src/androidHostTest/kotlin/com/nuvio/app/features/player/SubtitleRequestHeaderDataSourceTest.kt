package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nuvio.app.features.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleRequestHeaderDataSourceTest {
    @Test
    fun existingFactoryReadsLatestSubtitleHeadersWhenOpeningANewRequest() {
        val first = StreamSubtitle(
            url = "https://subtitles.example/track.vtt",
            language = "en",
            headers = mapOf("Authorization" to "Bearer old"),
        )
        val updated = first.copy(headers = mapOf("Authorization" to "Bearer refreshed"))
        var currentSubtitles = listOf(first)
        val upstream = RecordingDataSourceFactory()
        val factory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = upstream,
            externalSubtitlesProvider = { currentSubtitles },
        )
        val dataSource = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(first.url)).build()

        dataSource.open(spec)
        assertEquals("Bearer old", upstream.lastRequestHeaders?.get("Authorization"))
        dataSource.close()

        currentSubtitles = listOf(updated)
        dataSource.open(spec)

        assertEquals("Bearer refreshed", upstream.lastRequestHeaders?.get("Authorization"))
        dataSource.close()
    }

    @Test
    fun ordinaryMediaRequestsDoNotReceiveSubtitleSpecificHeaders() {
        val subtitle = StreamSubtitle(
            url = "https://subtitles.example/track.vtt",
            language = "en",
            headers = mapOf("Authorization" to "Bearer subtitle"),
        )
        val upstream = RecordingDataSourceFactory()
        val factory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = upstream,
            externalSubtitlesProvider = { listOf(subtitle) },
        )
        val dataSource = factory.createDataSource()

        dataSource.open(
            DataSpec.Builder()
                .setUri(Uri.parse("https://video.example/movie.m3u8"))
                .setHttpRequestHeaders(mapOf("User-Agent" to "Nuvio"))
                .build(),
        )

        assertEquals(mapOf("User-Agent" to "Nuvio"), upstream.lastRequestHeaders)
        dataSource.close()
    }

    private class RecordingDataSourceFactory : DataSource.Factory {
        var lastRequestHeaders: Map<String, String>? = null
            private set

        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                lastRequestHeaders = dataSpec.httpRequestHeaders
                return C.LENGTH_UNSET.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

            override fun getUri(): Uri? = null

            override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

            override fun close() = Unit
        }
    }
}
