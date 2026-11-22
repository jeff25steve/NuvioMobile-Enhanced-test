package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HlsPlaylistBoundTest {
    @Test
    fun knownOversizedPlaylistPassesThroughWithoutBeingBufferedDuringOpen() {
        val bytes = ByteArray(HLS_PLAYLIST_REWRITE_MAX_BYTES + 4096) { (it % 251).toByte() }
        val upstream = RecordingDataSourceFactory(bytes, resolveLength = true)
        val inspection = RecordingDataSourceFactory(ByteArray(0), resolveLength = true)
        val source = HlsParameterSetInitDataSource.Factory(upstream, inspection).createDataSource()

        try {
            assertEquals(bytes.size.toLong(), source.open(playlistSpec()))
            assertEquals(0, upstream.bytesRead)
            assertContentEquals(bytes, source.readAll())
            assertEquals(0, inspection.createCount)
        } finally {
            source.close()
        }
    }

    private fun playlistSpec() = DataSpec.Builder()
        .setUri(Uri.parse("https://host/live/index.m3u8"))
        .build()

    private fun DataSource.readAll(): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }

    private class RecordingDataSourceFactory(
        private val bytes: ByteArray,
        private val resolveLength: Boolean,
    ) : DataSource.Factory {
        var createCount = 0
            private set
        var bytesRead = 0
            private set

        override fun createDataSource(): DataSource {
            createCount++
            return object : DataSource {
                private var position = 0
                private var uri: Uri? = null

                override fun addTransferListener(transferListener: TransferListener) = Unit

                override fun open(dataSpec: DataSpec): Long {
                    uri = dataSpec.uri
                    position = dataSpec.position.toInt()
                    return if (resolveLength) (bytes.size - position).toLong() else C.LENGTH_UNSET.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (position >= bytes.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    bytesRead += count
                    return count
                }

                override fun getUri(): Uri? = uri
                override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
                override fun close() { uri = null }
            }
        }
    }
}
