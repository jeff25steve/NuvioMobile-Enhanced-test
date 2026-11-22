@file:OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SharedMemory
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val MB = 1024L * 1024L
private const val READ_AHEAD_BLOCK_BYTES = 16 shl 20

internal fun readAheadBlockSizeBytes(capacityBytes: Long, queuedBytes: Long): Int {
    require(capacityBytes > 0L) { "Read-ahead capacity must be positive" }
    require(queuedBytes in 0 until capacityBytes) { "Queued bytes must leave room for another block" }
    return minOf(READ_AHEAD_BLOCK_BYTES.toLong(), capacityBytes - queuedBytes).toInt()
}

internal fun isProgressivePlaybackSource(
    url: String,
    responseHeaders: Map<String, String>,
    streamType: String?,
): Boolean = Util.inferContentTypeForUriAndMimeType(
    Uri.parse(url),
    playbackMediaItemFromUrl(url, responseHeaders, streamType).localConfiguration?.mimeType,
) == C.CONTENT_TYPE_OTHER

/**
 * Select only independently classified progressive sources for VOD buffering.
 *
 * A separate audio URL has its own MIME hints in its URL. The video's response headers and
 * stream type must never be reused to classify that second resource.
 */
internal fun playbackBufferedUrls(
    videoUrl: String,
    audioUrl: String?,
    videoResponseHeaders: Map<String, String>,
    videoStreamType: String?,
): Set<String> = buildSet {
    if (isProgressivePlaybackSource(videoUrl, videoResponseHeaders, videoStreamType)) {
        add(videoUrl)
    }
    audioUrl
        ?.takeIf { it.isNotBlank() }
        // Without separate audio response headers, require a recognizable URL MIME hint.
        // Unknown/opaque URLs may be adaptive manifests, so fail closed instead of caching them.
        ?.takeIf { playbackMediaItemFromUrl(it).localConfiguration?.mimeType != null }
        ?.takeIf { isProgressivePlaybackSource(it, emptyMap(), null) }
        ?.let(::add)
}

internal fun PlayerSettingsUiState.buildPlaybackLoadControl(localFileSource: Boolean = false): DefaultLoadControl {
    val heapLimitBytes = PlaybackMemoryInfo.javaHeapBufferLimitMb() * MB
    val builder = DefaultLoadControl.Builder()
    when {
        // Downloaded files are read from disk, so a long buffer only wastes memory: keep it small
        // and let the byte cap win over the time thresholds (upstream offline playback fix).
        localFileSource && !customPlaybackBuffersEnabled -> builder
            .setBackBuffer(0, false)
            .setBufferDurationsMsForLocalPlayback(
                LOCAL_FILE_MIN_BUFFER_MS,
                LOCAL_FILE_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
            )
            .setTargetBufferBytes(minOf(LOCAL_FILE_TARGET_BUFFER_MB * MB, heapLimitBytes).toInt())
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
        customPlaybackBuffersEnabled -> {
            val startMs = playbackStartBufferSeconds * 1000
            val rebufferMs = playbackRebufferSeconds * 1000
            val minMs = maxOf(playbackMinBufferSeconds * 1000, startMs, rebufferMs)
            val maxMs = maxOf(playbackMaxBufferSeconds * 1000, minMs)
            builder
                .setBackBuffer(playbackBackBufferSeconds * 1000, true)
                .setBufferDurationsMsForStreaming(minMs, maxMs, startMs, rebufferMs)
                .setBufferDurationsMsForLocalPlayback(minMs, maxMs, startMs, rebufferMs)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
                .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
                .setTargetBufferBytes(minOf(playbackTargetBufferMb * MB, heapLimitBytes).toInt())
        }
        exoNativeMemoryEnabled -> builder
            .setBackBuffer(10_000, true)
            .setBufferDurationsMsForStreaming(
                120_000,
                600_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setBufferDurationsMsForLocalPlayback(
                120_000,
                600_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
            )
            .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
            .setTargetBufferBytes(heapLimitBytes.toInt())
        else -> builder
            .setBackBuffer(10_000, true)
            .setBufferDurationsMsForStreaming(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
    }
    return builder.build()
}

private fun PlayerSettingsUiState.nativeReadAheadBytes(): Long = when {
    !exoNativeMemoryEnabled -> 0L
    customPlaybackBuffersEnabled ->
        playbackTargetBufferMb.coerceAtMost(PlaybackMemoryInfo.safeBufferLimitMb()) * MB
    else -> PlaybackMemoryInfo.safeBufferLimitMb() / 2 * MB
}

internal fun DataSource.Factory.withPlaybackBuffering(
    context: Context,
    settings: PlayerSettingsUiState,
    bufferedUrls: Set<String>,
): DataSource.Factory {
    if (bufferedUrls.isEmpty() || (!settings.vodDiskCacheEnabled && !settings.exoNativeMemoryEnabled)) return this
    val plainFactory = this
    val cacheDirectory = context.cacheDir
    var bufferedFactory: DataSource.Factory = plainFactory
    if (settings.vodDiskCacheEnabled) {
        bufferedFactory = DataSource.Factory {
            CacheDataSource.Factory()
                .setCache(PlaybackDiskCache.get(context, settings))
                .setUpstreamDataSourceFactory(plainFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }
    }
    val readAheadBytes = settings.nativeReadAheadBytes()
    if (readAheadBytes > 0L) {
        val upstreamFactory = bufferedFactory
        bufferedFactory = DataSource.Factory {
            NativeReadAheadDataSource(upstreamFactory.createDataSource(), readAheadBytes, cacheDirectory)
        }
    }
    val finalBufferedFactory = bufferedFactory
    return DataSource.Factory {
        SelectiveBufferedDataSource(
            plainFactory = plainFactory,
            bufferedFactory = finalBufferedFactory,
            bufferedUrls = bufferedUrls,
        )
    }
}

private object PlaybackDiskCache {
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cache: SimpleCache? = null
    private var evictor: AdjustableLruCacheEvictor? = null
    private var settingsObserverStarted = false

    @Synchronized
    fun get(context: Context, settings: PlayerSettingsUiState): SimpleCache {
        val directory = File(context.cacheDir, "vod_playback_cache").apply { mkdirs() }
        val activeCache = cache
        if (activeCache != null) {
            // The settings argument is a player-composition snapshot and may be stale.
            // Once created, the live repository flow is the only authority for policy changes.
            // It also performs eviction on Dispatchers.IO rather than on the playback setup path.
            observeSettings(directory, activeCache, checkNotNull(evictor))
            return activeCache
        }

        val activeEvictor = AdjustableLruCacheEvictor(maxBytes(directory, settings))
        val createdCache = SimpleCache(
            directory,
            activeEvictor,
            StandaloneDatabaseProvider(context.applicationContext),
        )
        cache = createdCache
        evictor = activeEvictor
        observeSettings(directory, createdCache, activeEvictor)
        return createdCache
    }

    private fun observeSettings(
        directory: File,
        cache: SimpleCache,
        evictor: AdjustableLruCacheEvictor,
    ) {
        if (settingsObserverStarted) return
        settingsObserverStarted = true
        settingsScope.launch {
            PlayerSettingsRepository.uiState
                .map { it.vodDiskCacheAutoSize to it.vodDiskCacheSizeMb }
                .distinctUntilChanged()
                .collect { (autoSize, sizeMb) ->
                    evictor.setMaxBytes(cache, maxBytes(directory, autoSize, sizeMb))
                }
        }
    }

    private fun maxBytes(directory: File, settings: PlayerSettingsUiState): Long =
        maxBytes(directory, settings.vodDiskCacheAutoSize, settings.vodDiskCacheSizeMb)

    private fun maxBytes(directory: File, autoSize: Boolean, sizeMb: Int): Long =
        calculateVodDiskCacheMaxBytes(directory.usableSpace, autoSize, sizeMb)
}

internal fun calculateVodDiskCacheMaxBytes(
    usableSpaceBytes: Long,
    autoSize: Boolean,
    configuredSizeMb: Int,
): Long = if (autoSize) {
    (usableSpaceBytes / 5L).coerceIn(256L * MB, 10_240L * MB)
} else {
    configuredSizeMb.coerceAtLeast(1).toLong() * MB
}

/**
 * LRU eviction with a process-lifetime adjustable byte limit.
 *
 * Cache callbacks run while SimpleCache holds its monitor. Never hold [lock] while calling
 * Cache.removeSpan: settings updates can run on another thread and must not invert the
 * SimpleCache -> evictor callback lock order.
 */
internal class AdjustableLruCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    private val lock = Any()
    private val leastRecentlyUsed = TreeSet<CacheSpan> { left, right ->
        val timestampOrder = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        if (timestampOrder != 0) timestampOrder else left.compareTo(right)
    }

    @Volatile
    private var maxBytes = initialMaxBytes

    private var currentSize = 0L

    init {
        require(initialMaxBytes > 0L)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (leastRecentlyUsed.add(span)) currentSize += span.length
        }
        evictCache(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (leastRecentlyUsed.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        synchronized(lock) {
            leastRecentlyUsed.remove(oldSpan)
            leastRecentlyUsed.add(newSpan)
        }
        evictCache(cache, 0L)
    }

    fun setMaxBytes(cache: Cache, newMaxBytes: Long) {
        require(newMaxBytes > 0L)
        maxBytes = newMaxBytes
        evictCache(cache, 0L)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (true) {
            val oldestSpan = synchronized(lock) {
                if (currentSize + requiredSpace > maxBytes) {
                    leastRecentlyUsed.firstOrNull()
                } else {
                    null
                }
            } ?: return

            // removeSpan synchronously reports onSpanRemoved, which updates currentSize.
            // The cache call is deliberately outside the evictor lock to avoid lock inversion.
            cache.removeSpan(oldestSpan)
        }
    }
}

internal class SelectiveBufferedDataSource(
    private val plainFactory: DataSource.Factory,
    private val bufferedFactory: DataSource.Factory,
    private val bufferedUrls: Set<String>,
) : DataSource {
    private val transferListeners = ArrayList<TransferListener>()
    private var plain: DataSource? = null
    private var buffered: DataSource? = null
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        plain?.addTransferListener(transferListener)
        buffered?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val useBufferedSource = dataSpec.uri.toString() in bufferedUrls
        val selected = if (useBufferedSource) {
            buffered ?: bufferedFactory.createDataSource().also { created ->
                transferListeners.forEach(created::addTransferListener)
                buffered = created
            }
        } else {
            plain ?: plainFactory.createDataSource().also { created ->
                transferListeners.forEach(created::addTransferListener)
                plain = created
            }
        }
        active = selected
        return selected.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "DataSource.open must be called before read" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        active?.close()
        active = null
    }
}

private class NativeBlock(val buffer: ByteBuffer, private val onFree: () -> Unit) {
    fun free() = onFree()
}

private fun allocateNativeBlock(directory: File, sizeBytes: Int): NativeBlock {
    require(sizeBytes in 1..READ_AHEAD_BLOCK_BYTES)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val memory = SharedMemory.create("nuvio_read_ahead", sizeBytes)
        val mapped = memory.mapReadWrite()
        return NativeBlock(mapped) {
            SharedMemory.unmap(mapped)
            memory.close()
        }
    }
    val file = File.createTempFile("read_ahead", ".bin", directory)
    val randomAccessFile = RandomAccessFile(file, "rw")
    val mapped = randomAccessFile.channel.map(FileChannel.MapMode.READ_WRITE, 0, sizeBytes.toLong())
    file.delete()
    return NativeBlock(mapped) { runCatching { randomAccessFile.close() } }
}

private class NativeReadAheadDataSource(
    private val upstream: DataSource,
    private val capacityBytes: Long,
    private val directory: File,
) : DataSource {
    private class Chunk(val block: NativeBlock) {
        var written = 0
        var consumed = 0
    }

    private class Session {
        val lock = Object()
        val chunks = ArrayDeque<Chunk>()
        val freeBlocks = ArrayDeque<NativeBlock>()
        var queuedBytes = 0L
        var ended = false
        var failure: IOException? = null
        var readerDone = false
        var released = false

        @Volatile
        var closed = false

        fun releaseIfIdle() {
            if (!closed || !readerDone || released) return
            released = true
            chunks.forEach { it.block.free() }
            chunks.clear()
            freeBlocks.forEach { it.free() }
            freeBlocks.clear()
            queuedBytes = 0L
        }
    }

    private var session: Session? = null
    private var reader: Thread? = null
    private var openedUri: Uri? = null
    private var openedHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        session?.let { previous ->
            synchronized(previous.lock) {
                previous.closed = true
                previous.lock.notifyAll()
                previous.releaseIfIdle()
            }
        }
        reader?.let { previousReader ->
            if (previousReader.isAlive) previousReader.join(READER_SHUTDOWN_TIMEOUT_MS)
            if (previousReader.isAlive) {
                throw IOException("Previous read-ahead reader is still shutting down")
            }
        }
        reader = null
        session = null
        val length = upstream.open(dataSpec)
        openedUri = upstream.uri
        openedHeaders = upstream.responseHeaders
        val current = Session()
        session = current
        reader = Thread({ fill(current) }, "nuvio-native-read-ahead").apply {
            isDaemon = true
            start()
        }
        return length
    }

    private fun obtainBlock(current: Session, sizeBytes: Int): NativeBlock {
        synchronized(current.lock) { current.freeBlocks.removeLastOrNull() }?.let { return it }
        return try {
            allocateNativeBlock(directory, sizeBytes)
        } catch (error: Throwable) {
            throw IOException(error)
        }
    }

    private fun fill(current: Session) {
        val scratch = ByteArray(32 * 1024)
        var tail: Chunk? = null
        try {
            while (!current.closed) {
                val readLimit = synchronized(current.lock) {
                    while (!current.closed && current.queuedBytes >= capacityBytes) current.lock.wait()
                    if (current.closed) 0 else minOf(scratch.size.toLong(), capacityBytes - current.queuedBytes).toInt()
                }
                if (current.closed) break
                if (readLimit == 0) continue
                val read = upstream.read(scratch, 0, readLimit)
                if (read == C.RESULT_END_OF_INPUT) {
                    synchronized(current.lock) {
                        current.ended = true
                        current.lock.notifyAll()
                    }
                    break
                }
                var offset = 0
                while (offset < read) {
                    var chunk = tail
                    if (chunk == null || chunk.written == chunk.block.buffer.capacity()) {
                        val allocationSize = synchronized(current.lock) {
                            readAheadBlockSizeBytes(capacityBytes, current.queuedBytes)
                        }
                        val block = obtainBlock(current, allocationSize)
                        val newChunk = Chunk(block)
                        val accepted = synchronized(current.lock) {
                            if (current.closed) {
                                false
                            } else {
                                current.chunks.addLast(newChunk)
                                true
                            }
                        }
                        if (!accepted) {
                            block.free()
                            return
                        }
                        chunk = newChunk
                        tail = newChunk
                    }
                    val target: Chunk = chunk
                    val count = minOf(read - offset, target.block.buffer.capacity() - target.written)
                    target.block.buffer.duplicate().apply { position(target.written) }.put(scratch, offset, count)
                    synchronized(current.lock) {
                        target.written += count
                        current.queuedBytes += count
                        current.lock.notifyAll()
                    }
                    offset += count
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            synchronized(current.lock) {
                if (!current.closed) current.failure = error as? IOException ?: IOException(error)
                current.lock.notifyAll()
            }
        } finally {
            synchronized(current.lock) {
                current.readerDone = true
                current.lock.notifyAll()
                current.releaseIfIdle()
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val current = session ?: return C.RESULT_END_OF_INPUT
        return synchronized(current.lock) {
            var result = Int.MIN_VALUE
            while (result == Int.MIN_VALUE) {
                if (current.closed) throw IOException("Read-ahead source is closed")
                val head = current.chunks.firstOrNull()
                if (head != null && head.consumed < head.written) {
                    val count = minOf(length, head.written - head.consumed)
                    head.block.buffer.duplicate().apply { position(head.consumed) }.get(buffer, offset, count)
                    head.consumed += count
                    current.queuedBytes -= count
                    if (head.consumed == head.block.buffer.capacity()) {
                        current.chunks.removeFirst()
                        current.freeBlocks.addLast(head.block)
                    }
                    current.lock.notifyAll()
                    result = count
                    continue
                }
                current.failure?.let { throw it }
                if (current.ended || current.readerDone) {
                    result = C.RESULT_END_OF_INPUT
                    continue
                }
                try {
                    current.lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            result
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = openedHeaders

    override fun close() {
        session?.let { current ->
            synchronized(current.lock) {
                current.closed = true
                current.lock.notifyAll()
                current.releaseIfIdle()
            }
        }
        runCatching { upstream.close() }
        reader?.let { if (it.isAlive) it.join(READER_CLOSE_WAIT_MS) }
    }

    private companion object {
        const val READER_SHUTDOWN_TIMEOUT_MS = 5_000L
        const val READER_CLOSE_WAIT_MS = 500L
    }
}

private const val LOCAL_FILE_MIN_BUFFER_MS = 5_000
private const val LOCAL_FILE_MAX_BUFFER_MS = 15_000
private const val LOCAL_FILE_TARGET_BUFFER_MB = 48
