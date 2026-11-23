package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SelectiveBufferedDataSourceTest {
    @Test
    fun createsOnlyTheSelectedDataSourceWhenOpening() {
        val plain = RecordingFactory()
        val buffered = RecordingFactory()
        val source = SelectiveBufferedDataSource(
            plainFactory = plain,
            bufferedFactory = buffered,
            bufferedUrls = setOf("https://example.com/movie.mp4"),
        )

        assertEquals(0, plain.created)
        assertEquals(0, buffered.created)

        source.open(DataSpec.Builder().setUri(Uri.parse("https://example.com/movie.mp4")).build())

        assertEquals(0, plain.created)
        assertEquals(1, buffered.created)
        assertNotNull(source.uri)
        source.close()
    }

    @Test
    fun unbufferedUrlNeverInitializesTheCacheBackedDataSource() {
        val plain = RecordingFactory()
        val buffered = RecordingFactory()
        val source = SelectiveBufferedDataSource(
            plainFactory = plain,
            bufferedFactory = buffered,
            bufferedUrls = setOf("https://example.com/movie.mp4"),
        )

        source.open(DataSpec.Builder().setUri(Uri.parse("https://example.com/other.mp4")).build())

        assertEquals(1, plain.created)
        assertEquals(0, buffered.created)
        source.close()
    }

    @Test
    fun selectedDataSourceIsReusedAcrossOpenAndCloseCycles() {
        val plain = RecordingFactory()
        val buffered = RecordingFactory()
        val source = SelectiveBufferedDataSource(
            plainFactory = plain,
            bufferedFactory = buffered,
            bufferedUrls = setOf("https://example.com/movie.mp4"),
        )
        val spec = DataSpec.Builder().setUri(Uri.parse("https://example.com/movie.mp4")).build()

        source.open(spec)
        source.close()
        source.open(spec)

        assertEquals(0, plain.created)
        assertEquals(1, buffered.created)
        source.close()
    }


    @Test
    fun failedCloseClearsActiveSourceAndRecreatesTheBrokenDelegate() {
        val factory = FailsFirstCloseFactory()
        val source = SelectiveBufferedDataSource(
            plainFactory = factory,
            bufferedFactory = RecordingFactory(),
            bufferedUrls = emptySet(),
        )
        val spec = DataSpec.Builder().setUri(Uri.parse("https://example.com/movie.mp4")).build()

        source.open(spec)
        assertFailsWith<java.io.IOException> { source.close() }
        assertNull(source.uri)

        source.open(spec)
        assertEquals(2, factory.created)
        source.close()
    }

    private class FailsFirstCloseFactory : DataSource.Factory {
        var created = 0
            private set

        override fun createDataSource(): DataSource {
            val failClose = created++ == 0
            return object : DataSource {
                private var openedUri: Uri? = null
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long { openedUri = dataSpec.uri; return C.LENGTH_UNSET.toLong() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
                override fun getUri(): Uri? = openedUri
                override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
                override fun close() {
                    openedUri = null
                    if (failClose) throw java.io.IOException("simulated close failure")
                }
            }
        }
    }

    private class RecordingFactory : DataSource.Factory {
        var created = 0
            private set

        override fun createDataSource(): DataSource {
            created += 1
            return RecordingDataSource()
        }
    }

    private class RecordingDataSource : DataSource {
        private var openedUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openedUri = dataSpec.uri
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = openedUri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            openedUri = null
        }
    }
}
