package com.nuvio.app.features.player

import android.net.Uri
import android.util.Log
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nuvio.app.core.logging.InAppLogger
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Gives HLS media playlists the init segment from [HlsParameterSetInit] when their segments need
 * it. Playlists are read whole and handed on, rewritten or not. Everything else passes straight
 * through, including the `data:` init segment, which [upstream] must therefore be able to open
 * (a DefaultDataSource can).
 */
internal const val HLS_SEGMENT_INSPECTION_CALL_TIMEOUT_MS = 2_500L
internal const val HLS_PLAYLIST_REWRITE_MAX_BYTES = 256 * 1024

@OptIn(UnstableApi::class)
internal class HlsParameterSetInitDataSource(
    private val upstream: DataSource,
    private val createSegmentSource: () -> DataSource
) : DataSource {

    private var playlist: ByteArray? = null
    private var playlistReadPosition = 0
    private var playlistContinuesUpstreamAfterBuffer = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        playlist = null
        playlistReadPosition = 0
        playlistContinuesUpstreamAfterBuffer = false
        val length = upstream.open(dataSpec)
        if (dataSpec.position != 0L || !isPlaylist(dataSpec.uri, upstream.responseHeaders)) return length

        // A known oversized playlist can be passed through without reading it during open().
        if (length > HLS_PLAYLIST_REWRITE_MAX_BYTES) return length

        // Unknown-length responses are inspected only up to the strict memory budget plus one byte.
        val body = readPlaylistPrefix(upstream)
        if (body.size > HLS_PLAYLIST_REWRITE_MAX_BYTES) {
            playlist = body
            playlistContinuesUpstreamAfterBuffer = true
            Log.w(TAG, "HLS playlist exceeds rewrite limit=$HLS_PLAYLIST_REWRITE_MAX_BYTES bytes; passing through")
            return length
        }
        val baseUri = (upstream.uri ?: dataSpec.uri).toString()
        // Healthy streams pay for this check too, so a slow segment host must not hold up the start.
        val inspectionStartedAtMs = SystemClock.elapsedRealtime()
        var inspectionTimedOut = false
        val rewritten = resultWithin(CHECK_TIMEOUT_MS, onTimeout = { inspectionTimedOut = true }) {
            HlsParameterSetInit.withParameterSetInit(body.toString(Charsets.UTF_8), baseUri, ::fetchSegmentHead)
        }
        val inspectionElapsedMs = (SystemClock.elapsedRealtime() - inspectionStartedAtMs).coerceAtLeast(0L)
        Log.i(
            TAG,
            "HLS playlist inspection elapsedMs=$inspectionElapsedMs playlistBytes=${body.size} " +
                "rewritten=${rewritten != null} timedOut=$inspectionTimedOut",
        )
        if (rewritten != null) Log.i(TAG, "Added an SPS/PPS init segment")
        val served = rewritten?.toByteArray(Charsets.UTF_8) ?: body
        playlist = served
        return served.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = playlist ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val remaining = data.size - playlistReadPosition
        if (remaining <= 0) {
            return if (playlistContinuesUpstreamAfterBuffer) {
                upstream.read(buffer, offset, length)
            } else {
                C.RESULT_END_OF_INPUT
            }
        }
        val count = minOf(length, remaining)
        data.copyInto(buffer, offset, playlistReadPosition, playlistReadPosition + count)
        playlistReadPosition += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        playlist = null
        playlistContinuesUpstreamAfterBuffer = false
        upstream.close()
    }

    private fun fetchSegmentHead(uri: String): ByteArray? {
        val source = createSegmentSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse(uri))
                    .setLength(HlsParameterSetInit.SEGMENT_HEAD_BYTES.toLong())
                    .build()
            )
            // Some hosts throttle segments, so stop as soon as the start of the segment says enough.
            val head = ByteArrayOutputStream()
            val step = ByteArray(SEGMENT_HEAD_STEP_BYTES)
            while (head.size() < HlsParameterSetInit.SEGMENT_HEAD_BYTES) {
                val read = source.read(step, 0, minOf(step.size, HlsParameterSetInit.SEGMENT_HEAD_BYTES - head.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                head.write(step, 0, read)
                if (HlsParameterSetInit.isSegmentHeadConclusive(head.toByteArray())) break
            }
            head.toByteArray()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not read the start of ${InAppLogger.redactUrl(uri)}", e)
            null
        } finally {
            runCatching { source.close() }
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val segmentInspectionFactory: DataSource.Factory = upstreamFactory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HlsParameterSetInitDataSource(
                upstreamFactory.createDataSource(),
                segmentInspectionFactory::createDataSource,
            )
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 3_000L
        const val SEGMENT_HEAD_STEP_BYTES = 4 * 1024
        const val PLAYLIST_READ_CHUNK_BYTES = 16 * 1024

        fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
            return headers.entries.any { (name, values) ->
                name.equals("Content-Type", ignoreCase = true) && values.any { it.contains("mpegurl", ignoreCase = true) }
            }
        }

        fun readPlaylistPrefix(source: DataSource): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(PLAYLIST_READ_CHUNK_BYTES)
            val limitWithSentinel = HLS_PLAYLIST_REWRITE_MAX_BYTES + 1
            while (out.size() < limitWithSentinel) {
                val read = source.read(chunk, 0, minOf(chunk.size, limitWithSentinel - out.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }
}

private const val TAG = "HlsParameterSetInit"

/**
 * Runs [block] on its own thread and returns its result, or null when it fails or [timeoutMs]
 * passes first. A block that overruns is interrupted and left to finish in the background.
 */
internal fun <T : Any> resultWithin(timeoutMs: Long, onTimeout: () -> Unit = {}, block: () -> T?): T? {
    val task = FutureTask(Callable { block() })
    Thread(task, TAG).apply { isDaemon = true }.start()
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        onTimeout()
        Log.w(TAG, "Checking segments took over $timeoutMs ms, playlist left as is")
        null
    } catch (e: ExecutionException) {
        Log.w(TAG, "Checking segments failed, playlist left as is", e.cause)
        null
    } catch (e: InterruptedException) {
        task.cancel(true)
        Thread.currentThread().interrupt()
        throw InterruptedIOException()
    }
}
