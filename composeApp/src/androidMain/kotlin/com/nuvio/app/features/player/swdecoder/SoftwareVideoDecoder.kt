package com.nuvio.app.features.player.swdecoder

import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal const val FFMPEG_INPUT_PADDING_SIZE = 64
internal const val SOFTWARE_DECODER_RESULT_OK = 0
internal const val SOFTWARE_DECODER_RESULT_SKIP = 1
internal const val SOFTWARE_DECODER_RESULT_ERROR = -1
internal const val SOFTWARE_DECODER_RESULT_AGAIN = 2
internal const val SOFTWARE_DECODER_RESULT_EOF = 3

internal enum class SoftwareDecoderWorkAction {
    TAKE_INPUT, SEND_PACKET, SEND_EOS, RECEIVE_FRAME, WAIT, FLUSH, STOP,
}

/** Small deterministic state machine for FFmpeg's send/receive contract. */
internal class SoftwareVideoDecoderFlowState {
    var packetPending = false
        private set
    var eosPending = false
        private set
    var receivePending = false
        private set
    var draining = false
        private set
    var ended = false
        private set
    private var sendBlockedAwaitingFrame = false
    private var queuedInputSampleCount = 0L
    private var producedFrameCount = 0L

    fun onInputSampleQueued() {
        if (queuedInputSampleCount < Long.MAX_VALUE) queuedInputSampleCount++
    }

    fun onFrameProduced() {
        if (producedFrameCount < Long.MAX_VALUE) producedFrameCount++
    }

    fun drainUnmatchedInputSamples(): Int {
        val missing = (queuedInputSampleCount - producedFrameCount).coerceAtLeast(0L)
        queuedInputSampleCount = 0L
        producedFrameCount = 0L
        return missing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun nextAction(hasQueuedInput: Boolean, hasAvailableOutput: Boolean): SoftwareDecoderWorkAction =
        when {
            ended -> SoftwareDecoderWorkAction.WAIT
            receivePending && hasAvailableOutput -> SoftwareDecoderWorkAction.RECEIVE_FRAME
            receivePending -> SoftwareDecoderWorkAction.WAIT
            packetPending -> SoftwareDecoderWorkAction.SEND_PACKET
            eosPending -> SoftwareDecoderWorkAction.SEND_EOS
            hasQueuedInput -> SoftwareDecoderWorkAction.TAKE_INPUT
            else -> SoftwareDecoderWorkAction.WAIT
        }

    fun queuePacket() {
        check(!packetPending && !eosPending && !draining && !ended)
        packetPending = true
    }

    fun queueEndOfStream() {
        check(!packetPending && !eosPending && !draining && !ended)
        eosPending = true
    }

    fun onPacketSendResult(result: Int) {
        check(packetPending)
        when (result) {
            SOFTWARE_DECODER_RESULT_OK -> {
                packetPending = false
                sendBlockedAwaitingFrame = false
                receivePending = true
            }
            SOFTWARE_DECODER_RESULT_AGAIN -> {
                sendBlockedAwaitingFrame = true
                receivePending = true
            }
            SOFTWARE_DECODER_RESULT_SKIP -> {
                packetPending = false
                sendBlockedAwaitingFrame = false
            }
            else -> error("Unexpected FFmpeg packet-send result: " + result)
        }
    }

    fun onEosSendResult(result: Int) {
        check(eosPending)
        when (result) {
            SOFTWARE_DECODER_RESULT_OK -> {
                eosPending = false
                sendBlockedAwaitingFrame = false
                draining = true
                receivePending = true
            }
            SOFTWARE_DECODER_RESULT_AGAIN -> {
                sendBlockedAwaitingFrame = true
                receivePending = true
            }
            else -> error("Unexpected FFmpeg EOS-send result: " + result)
        }
    }

    fun onFrameReceiveResult(result: Int) {
        when (result) {
            SOFTWARE_DECODER_RESULT_OK -> {
                sendBlockedAwaitingFrame = false
                receivePending = true
            }
            SOFTWARE_DECODER_RESULT_AGAIN -> {
                check(!draining) { "FFmpeg returned EAGAIN after entering drain mode" }
                check(!sendBlockedAwaitingFrame) {
                    "FFmpeg returned EAGAIN from both send and receive without making progress"
                }
                receivePending = false
            }
            SOFTWARE_DECODER_RESULT_EOF -> {
                check(draining) { "FFmpeg returned EOF before drain mode" }
                draining = false
                sendBlockedAwaitingFrame = false
                receivePending = false
                ended = true
            }
            else -> error("Unexpected FFmpeg frame-receive result: " + result)
        }
    }

    fun reset() {
        packetPending = false
        eosPending = false
        receivePending = false
        draining = false
        sendBlockedAwaitingFrame = false
        ended = false
        queuedInputSampleCount = 0L
        producedFrameCount = 0L
    }
}

internal fun softwareFrameColorSpace(frameInfo: LongArray): Int = frameInfo[4].toInt()

/**
 * Builds Android's display dataspace from FFmpeg's independent primaries, transfer, and range
 * metadata. Matrix coefficients remain separate: they describe YUV conversion, not display gamut.
 */
internal fun softwareFrameDataSpace(
    colorPrimaries: Int,
    colorTransfer: Int,
    colorRange: Int,
    matrixColorSpace: Int,
    frameHeight: Int,
): Int {
    if (
        colorPrimaries == AVCOL_PRI_SMPTE432 &&
        colorTransfer == AVCOL_TRC_IEC61966_2_1 &&
        colorRange == AVCOL_RANGE_JPEG
    ) {
        // Android's named DISPLAY_P3 dataspace is DCI-P3 primaries + sRGB transfer + full range.
        return DATASPACE_DISPLAY_P3
    }
    val standard = when (colorPrimaries) {
        AVCOL_PRI_BT709 -> DATASPACE_STANDARD_BT709
        AVCOL_PRI_BT470M -> DATASPACE_STANDARD_BT470M
        AVCOL_PRI_BT470BG -> DATASPACE_STANDARD_BT601_625
        AVCOL_PRI_SMPTE170M, AVCOL_PRI_SMPTE240M -> DATASPACE_STANDARD_BT601_525
        AVCOL_PRI_FILM -> DATASPACE_STANDARD_FILM
        AVCOL_PRI_BT2020 -> DATASPACE_STANDARD_BT2020
        AVCOL_PRI_SMPTE431 -> DATASPACE_STANDARD_DCI_P3
        else -> when (matrixColorSpace) {
            SOFTWARE_COLORSPACE_BT2020 -> DATASPACE_STANDARD_BT2020
            SOFTWARE_COLORSPACE_BT601 -> if (frameHeight == 480) {
                DATASPACE_STANDARD_BT601_525
            } else {
                DATASPACE_STANDARD_BT601_625
            }
            else -> DATASPACE_STANDARD_BT709
        }
    }
    val transfer = when (colorTransfer) {
        AVCOL_TRC_BT709, AVCOL_TRC_SMPTE240M, AVCOL_TRC_BT2020_10, AVCOL_TRC_BT2020_12,
        AVCOL_TRC_IEC61966_2_4, AVCOL_TRC_BT1361_ECG -> DATASPACE_TRANSFER_SMPTE_170M
        AVCOL_TRC_LINEAR -> DATASPACE_TRANSFER_LINEAR
        AVCOL_TRC_IEC61966_2_1 -> DATASPACE_TRANSFER_SRGB
        AVCOL_TRC_GAMMA22 -> DATASPACE_TRANSFER_GAMMA_2_2
        AVCOL_TRC_SMPTE428 -> DATASPACE_TRANSFER_GAMMA_2_6
        AVCOL_TRC_GAMMA28 -> DATASPACE_TRANSFER_GAMMA_2_8
        AVCOL_TRC_SMPTE2084 -> DATASPACE_TRANSFER_ST2084
        AVCOL_TRC_ARIB_STD_B67 -> DATASPACE_TRANSFER_HLG
        else -> DATASPACE_TRANSFER_SMPTE_170M
    }
    val range = if (colorRange == AVCOL_RANGE_JPEG) {
        DATASPACE_RANGE_FULL
    } else {
        DATASPACE_RANGE_LIMITED
    }
    return standard or transfer or range
}

internal fun resolveSoftwareFrameTimestampUs(ptsUs: Long, bestEffortTimestampUs: Long, packetTimestampUs: Long): Long =
    when {
        ptsUs != Long.MIN_VALUE -> ptsUs
        bestEffortTimestampUs != Long.MIN_VALUE -> bestEffortTimestampUs
        else -> packetTimestampUs
    }

internal fun setOutputFrameDataSpace(outputBuffer: VideoDecoderOutputBuffer, dataSpace: Int) {
    outputBuffer.decoderPrivate = dataSpace.toLong()
}

internal fun setPrivateSurfaceFrameDataSpace(outputBuffer: VideoDecoderOutputBuffer, dataSpace: Int) {
    // In surface-YUV mode Media3 does not consume colorspace; decoderPrivate owns the AVFrame handle.
    outputBuffer.colorspace = dataSpace
}

internal fun privateSurfaceFrameDataSpace(outputBuffer: VideoDecoderOutputBuffer): Int =
    outputBuffer.colorspace

private const val AVCOL_PRI_BT709 = 1
private const val AVCOL_PRI_BT470M = 4
private const val AVCOL_PRI_BT470BG = 5
private const val AVCOL_PRI_SMPTE170M = 6
private const val AVCOL_PRI_SMPTE240M = 7
private const val AVCOL_PRI_FILM = 8
private const val AVCOL_PRI_BT2020 = 9
private const val AVCOL_PRI_SMPTE431 = 11
private const val AVCOL_PRI_SMPTE432 = 12

private const val AVCOL_TRC_BT709 = 1
private const val AVCOL_TRC_GAMMA22 = 4
private const val AVCOL_TRC_GAMMA28 = 5
private const val AVCOL_TRC_SMPTE240M = 7
private const val AVCOL_TRC_LINEAR = 8
private const val AVCOL_TRC_IEC61966_2_4 = 11
private const val AVCOL_TRC_BT1361_ECG = 12
private const val AVCOL_TRC_IEC61966_2_1 = 13
private const val AVCOL_TRC_BT2020_10 = 14
private const val AVCOL_TRC_BT2020_12 = 15
private const val AVCOL_TRC_SMPTE2084 = 16
private const val AVCOL_TRC_SMPTE428 = 17
private const val AVCOL_TRC_ARIB_STD_B67 = 18

private const val AVCOL_RANGE_JPEG = 2
private const val SOFTWARE_COLORSPACE_BT601 = 1
private const val SOFTWARE_COLORSPACE_BT2020 = 3

private const val DATASPACE_STANDARD_BT709 = 1 shl 16
private const val DATASPACE_STANDARD_BT601_625 = 2 shl 16
private const val DATASPACE_STANDARD_BT601_525 = 4 shl 16
private const val DATASPACE_STANDARD_BT2020 = 6 shl 16
private const val DATASPACE_STANDARD_BT470M = 8 shl 16
private const val DATASPACE_STANDARD_FILM = 9 shl 16
private const val DATASPACE_STANDARD_DCI_P3 = 10 shl 16
private const val DATASPACE_DISPLAY_P3 =
    DATASPACE_STANDARD_DCI_P3 or (2 shl 22) or (1 shl 27)

private const val DATASPACE_TRANSFER_LINEAR = 1 shl 22
private const val DATASPACE_TRANSFER_SRGB = 2 shl 22
private const val DATASPACE_TRANSFER_SMPTE_170M = 3 shl 22
private const val DATASPACE_TRANSFER_GAMMA_2_2 = 4 shl 22
private const val DATASPACE_TRANSFER_GAMMA_2_6 = 5 shl 22
private const val DATASPACE_TRANSFER_GAMMA_2_8 = 6 shl 22
private const val DATASPACE_TRANSFER_ST2084 = 7 shl 22
private const val DATASPACE_TRANSFER_HLG = 8 shl 22

private const val DATASPACE_RANGE_FULL = 1 shl 27
private const val DATASPACE_RANGE_LIMITED = 2 shl 27

/** Transfers a native-owned decoded frame out of the Media3 output buffer exactly once. */
internal fun takeSoftwarePrivateFrameHandle(outputBuffer: VideoDecoderOutputBuffer): Long {
    val handle = outputBuffer.decoderPrivate
    outputBuffer.decoderPrivate = 0L
    return handle
}

/** Bounded, lock-confined pool for reusable AVFrame owner structs. */
internal class SoftwareVideoFrameHandlePool(private val capacity: Int) {
    private val handles = ArrayDeque<Long>(capacity)

    init {
        require(capacity > 0)
    }

    fun acquire(): Long = if (handles.isEmpty()) 0L else handles.removeFirst()

    /** Returns false when the caller must free [handle] instead of retaining it. */
    fun recycle(handle: Long): Boolean {
        if (handle == 0L || handles.size >= capacity) return false
        handles.addLast(handle)
        return true
    }

    fun drain(): List<Long> = buildList {
        while (handles.isNotEmpty()) add(handles.removeFirst())
    }
}

internal fun usesSoftwarePrivateSurfaceFrame(outputMode: Int): Boolean =
    outputMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV

/** Releases any previous GL/YUV output storage before a buffer becomes a native surface frame. */
internal fun clearSoftwareYuvOutputStorage(outputBuffer: VideoDecoderOutputBuffer) {
    outputBuffer.data = null
    outputBuffer.yuvPlanes = null
    outputBuffer.yuvStrides = null
}

internal fun supportsYv12SurfaceSize(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0

internal fun isSoftwareFrameBeforeOutputStart(timeUs: Long, outputStartTimeUs: Long): Boolean =
    outputStartTimeUs != C.TIME_UNSET && timeUs < outputStartTimeUs

internal fun shouldConvertSoftwareFrame(outputMode: Int): Boolean =
    outputMode != C.VIDEO_OUTPUT_MODE_NONE

internal fun addSkippedOutputBufferCount(current: Int, additional: Int): Int =
    (current.toLong() + additional.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Returns the byte capacity required by a described I420 frame, or null for invalid layouts. */
internal fun requiredSoftwareYuv420BufferSize(width: Int, height: Int, yStride: Int, uvStride: Int): Long? {
    if (width <= 0 || height <= 0 || yStride < width || uvStride < (width / 2) + (width % 2)) return null
    val yBytes = yStride.toLong() * height.toLong()
    val chromaBytes = uvStride.toLong() * ((height.toLong() + 1L) / 2L)
    if (chromaBytes > Long.MAX_VALUE / 2L) return null
    val bothChromaPlanes = chromaBytes * 2L
    if (yBytes > Long.MAX_VALUE - bothChromaPlanes) return null
    val requiredBytes = yBytes + bothChromaPlanes
    return requiredBytes.takeIf { it <= Int.MAX_VALUE.toLong() }
}

/** Ensures FFmpeg's required zeroed overread padding without copying the compressed sample. */
internal fun prepareFfmpegPacketData(data: ByteBuffer): Int {
    require(data.isDirect) { "FFmpeg packet input must be a direct buffer" }
    require(data.position() == 0) { "FFmpeg packet input must be positioned at the sample start" }
    val size = data.limit()
    require(size <= Int.MAX_VALUE - FFMPEG_INPUT_PADDING_SIZE) {
        "FFmpeg packet input is too large to include its required padding"
    }
    require(data.capacity() >= size + FFMPEG_INPUT_PADDING_SIZE) {
        "FFmpeg packet input is missing its required padding"
    }
    val sampleLimit = data.limit()
    data.limit(data.capacity())
    for (index in sampleLimit until sampleLimit + FFMPEG_INPUT_PADDING_SIZE) {
        data.put(index, 0)
    }
    data.limit(sampleLimit)
    return size
}

internal class SoftwareVideoDecoderException(
    message: String,
    cause: Throwable? = null,
) : DecoderException(message, cause)

@UnstableApi
internal class SoftwareVideoDecoder(
    private val format: Format,
    threads: Int,
) : Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, SoftwareVideoDecoderException>,
    VideoDecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {

    private val codecName = codecNameFor(format.sampleMimeType)
        ?: throw SoftwareVideoDecoderException("Unsupported software video format: " + format.sampleMimeType)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val availableInputBuffers = ArrayDeque<DecoderInputBuffer>(INPUT_BUFFERS)
    private val queuedInputBuffers = ArrayDeque<DecoderInputBuffer>(INPUT_BUFFERS)
    private val availableOutputBuffers = ArrayDeque<VideoDecoderOutputBuffer>(OUTPUT_BUFFERS)
    private val queuedOutputBuffers = ArrayDeque<VideoDecoderOutputBuffer>(OUTPUT_BUFFERS)
    // AVFrame owner structs return here when Media3 releases their output buffers.
    private val reusableFrameHandles = SoftwareVideoFrameHandlePool(OUTPUT_BUFFERS)
    private val flow = SoftwareVideoDecoderFlowState()
    private val frameInfo = LongArray(8)

    private var dequeuedInputBuffer: DecoderInputBuffer? = null
    @Volatile private var pendingInputBuffer: DecoderInputBuffer? = null
    private var callback: Decoder.Callback? = null
    private var callbackExecutor: Executor? = null
    private var exception: SoftwareVideoDecoderException? = null
    private var outputStartTimeUs: Long = C.TIME_UNSET
    private var flushRequested = false
    private var hasQueuedInputSinceFlush = false
    private var released = false
    private var skippedOutputBufferCount = 0
    private var firstSamplePending = false
    private var lastPacketTimestampUs = C.TIME_UNSET
    @Volatile private var handle = 0L

    @Volatile
    var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    private val worker = Thread({ runWorker() }, "Nuvio:SoftwareVideoDecoder")

    init {
        repeat(INPUT_BUFFERS) {
            availableInputBuffers.addLast(
                DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FFMPEG_INPUT_PADDING_SIZE),
            )
        }
        repeat(OUTPUT_BUFFERS) {
            availableOutputBuffers.addLast(VideoDecoderOutputBuffer(this))
        }
        handle = if (isAvailable) nativeCreate(codecName, extraData(format), threads) else 0L
        if (handle == 0L) {
            throw SoftwareVideoDecoderException("Failed to initialize software decoder for " + format.sampleMimeType)
        }
        worker.start()
    }

    override fun getName(): String = "nuvio-sw-" + codecName

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        synchronized(lock) {
            check(
                !hasQueuedInputSinceFlush,
            ) { "Output start time may only be set before input or after flush" }
            this.outputStartTimeUs = outputStartTimeUs
        }
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? = synchronized(lock) {
        maybeThrowExceptionLocked()
        check(dequeuedInputBuffer == null) { "An input buffer is already dequeued" }
        if (availableInputBuffers.isEmpty() || released) {
            null
        } else {
            availableInputBuffers.removeFirst().also {
                it.clear()
                dequeuedInputBuffer = it
            }
        }
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        synchronized(lock) {
            maybeThrowExceptionLocked()
            check(dequeuedInputBuffer === inputBuffer) { "Input buffer was not dequeued from this decoder" }
            dequeuedInputBuffer = null
            queuedInputBuffers.addLast(inputBuffer)
            hasQueuedInputSinceFlush = true
            lock.notifyAll()
        }
    }

    override fun dequeueOutputBuffer(): VideoDecoderOutputBuffer? = synchronized(lock) {
        maybeThrowExceptionLocked()
        if (queuedOutputBuffers.isEmpty()) null else queuedOutputBuffers.removeFirst()
    }

    override fun releaseOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        synchronized(lock) {
            recycleOutputBufferLocked(outputBuffer)
        }
    }

    override fun setCallback(callback: Decoder.Callback, executor: Executor) {
        synchronized(lock) {
            this.callback = callback
            callbackExecutor = executor
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (released) return
            flushRequested = true
            exception = null
            hasQueuedInputSinceFlush = false
            dequeuedInputBuffer?.let {
                recycleInputBufferLocked(it)
                dequeuedInputBuffer = null
            }
            while (queuedInputBuffers.isNotEmpty()) {
                recycleInputBufferLocked(queuedInputBuffers.removeFirst())
            }
            while (queuedOutputBuffers.isNotEmpty()) {
                recycleOutputBufferLocked(queuedOutputBuffers.removeFirst())
            }
            lock.notifyAll()
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            callback = null
            callbackExecutor = null
            dequeuedInputBuffer?.clear()
            dequeuedInputBuffer = null
            while (queuedInputBuffers.isNotEmpty()) queuedInputBuffers.removeFirst().clear()
            while (queuedOutputBuffers.isNotEmpty()) recycleOutputBufferLocked(queuedOutputBuffers.removeFirst())
            lock.notifyAll()
        }
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw SoftwareVideoDecoderException("Invalid software video surface output mode")
        }
        if (!supportsYv12SurfaceSize(outputBuffer.width, outputBuffer.height)) {
            throw SoftwareVideoDecoderException("YV12 surface output requires positive even frame dimensions")
        }
        val decoderHandle = handle
        if (decoderHandle == 0L) {
            throw SoftwareVideoDecoderException("Software video decoder has been released")
        }
        val frameHandle = outputBuffer.decoderPrivate
        if (frameHandle == 0L) {
            throw SoftwareVideoDecoderException("Software video output frame has no native owner")
        }
        val result = nativeRenderPrivateFrame(decoderHandle, frameHandle, surface, privateSurfaceFrameDataSpace(outputBuffer))
        if (result == SOFTWARE_DECODER_RESULT_ERROR) {
            throw SoftwareVideoDecoderException("Failed to render " + codecName + " frame")
        }
    }

    private fun runWorker() {
        try {
            while (true) {
                val work = awaitWork()
                if (work.action == SoftwareDecoderWorkAction.STOP) return
                try {
                    when (work.action) {
                        SoftwareDecoderWorkAction.STOP -> return
                        SoftwareDecoderWorkAction.WAIT -> Unit
                        SoftwareDecoderWorkAction.TAKE_INPUT -> error("Input selection was not completed")
                        SoftwareDecoderWorkAction.SEND_PACKET -> sendPendingPacket()
                        SoftwareDecoderWorkAction.SEND_EOS -> sendEndOfStream()
                        SoftwareDecoderWorkAction.RECEIVE_FRAME -> receiveFrame(checkNotNull(work.outputBuffer))
                        SoftwareDecoderWorkAction.FLUSH -> processFlush()
                    }
                } catch (error: RuntimeException) {
                    work.outputBuffer?.let(::recycleOutputBuffer)
                    fail(
                        SoftwareVideoDecoderException("Unexpected software decode error", error),
                        ignoreIfFlushing = work.action != SoftwareDecoderWorkAction.FLUSH,
                    )
                } catch (error: OutOfMemoryError) {
                    work.outputBuffer?.let(::recycleOutputBuffer)
                    fail(
                        SoftwareVideoDecoderException("Software video decoder ran out of memory", error),
                        ignoreIfFlushing = work.action != SoftwareDecoderWorkAction.FLUSH,
                    )
                }
            }
        } finally {
            pendingInputBuffer?.clear()
            pendingInputBuffer = null
            synchronized(lock) {
                while (queuedInputBuffers.isNotEmpty()) queuedInputBuffers.removeFirst().clear()
                while (queuedOutputBuffers.isNotEmpty()) recycleOutputBufferLocked(queuedOutputBuffers.removeFirst())
                availableInputBuffers.clear()
                availableOutputBuffers.clear()
                reusableFrameHandles.drain().forEach { frameHandle -> nativeReleaseFrame(frameHandle) }
                lock.notifyAll()
            }
            val currentHandle = handle
            handle = 0L
            if (currentHandle != 0L) nativeRelease(currentHandle)
        }
    }

    private fun awaitWork(): Work = synchronized(lock) {
        while (true) {
            if (released) return@synchronized Work(SoftwareDecoderWorkAction.STOP)
            if (flushRequested) return@synchronized Work(SoftwareDecoderWorkAction.FLUSH)
            if (exception != null) {
                lock.wait()
                continue
            }
            when (flow.nextAction(queuedInputBuffers.isNotEmpty(), availableOutputBuffers.isNotEmpty())) {
                SoftwareDecoderWorkAction.STOP -> return@synchronized Work(SoftwareDecoderWorkAction.STOP)
                SoftwareDecoderWorkAction.RECEIVE_FRAME ->
                    return@synchronized Work(
                        SoftwareDecoderWorkAction.RECEIVE_FRAME,
                        outputBuffer = availableOutputBuffers.removeFirst(),
                    )
                SoftwareDecoderWorkAction.SEND_PACKET ->
                    return@synchronized Work(SoftwareDecoderWorkAction.SEND_PACKET)
                SoftwareDecoderWorkAction.SEND_EOS ->
                    return@synchronized Work(SoftwareDecoderWorkAction.SEND_EOS)
                SoftwareDecoderWorkAction.TAKE_INPUT -> {
                    val input = queuedInputBuffers.removeFirst()
                    if (input.isEndOfStream) {
                        flow.queueEndOfStream()
                        recycleInputBufferLocked(input)
                    } else {
                        pendingInputBuffer = input
                        flow.onInputSampleQueued()
                        if (input.isFirstSample) firstSamplePending = true
                        flow.queuePacket()
                    }
                }
                SoftwareDecoderWorkAction.WAIT -> lock.wait()
                SoftwareDecoderWorkAction.FLUSH -> return@synchronized Work(SoftwareDecoderWorkAction.FLUSH)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Work(SoftwareDecoderWorkAction.STOP)
    }

    private fun sendPendingPacket() {
        if (isFlushRequested()) return
        val input = checkNotNull(pendingInputBuffer)
        val data = input.data ?: throw SoftwareVideoDecoderException("Missing " + codecName + " packet data")
        val sampleSize = prepareFfmpegPacketData(data)
        val result = nativeSendPacket(handle, data, sampleSize, input.timeUs)
        if (result != SOFTWARE_DECODER_RESULT_OK &&
            result != SOFTWARE_DECODER_RESULT_AGAIN &&
            result != SOFTWARE_DECODER_RESULT_SKIP
        ) {
            throw SoftwareVideoDecoderException("Failed to send " + codecName + " packet (" + result + ")")
        }
        flow.onPacketSendResult(result)
        when (result) {
            SOFTWARE_DECODER_RESULT_OK -> {
                lastPacketTimestampUs = input.timeUs
                pendingInputBuffer = null
                synchronized(lock) { recycleInputBufferLocked(input) }
                dispatchInputBufferAvailable()
            }
            SOFTWARE_DECODER_RESULT_AGAIN -> Unit
            SOFTWARE_DECODER_RESULT_SKIP -> {
                // The input sample remains unmatched and is accounted for at EOS if it
                // never produces a frame; do not count it twice here.
                pendingInputBuffer = null
                synchronized(lock) { recycleInputBufferLocked(input) }
                dispatchInputBufferAvailable()
            }
        }
    }

    private fun sendEndOfStream() {
        if (isFlushRequested()) return
        val result = nativeSendEndOfStream(handle)
        if (result != SOFTWARE_DECODER_RESULT_OK && result != SOFTWARE_DECODER_RESULT_AGAIN) {
            throw SoftwareVideoDecoderException("Failed to signal " + codecName + " end of stream (" + result + ")")
        }
        flow.onEosSendResult(result)
    }

    private fun receiveFrame(outputBuffer: VideoDecoderOutputBuffer) {
        if (isFlushRequested()) {
            recycleOutputBuffer(outputBuffer)
            return
        }
        val result = nativeReceiveFrame(handle, frameInfo)
        if (result == SOFTWARE_DECODER_RESULT_ERROR) {
            throw SoftwareVideoDecoderException("Failed to receive " + codecName + " frame")
        }
        if (result == SOFTWARE_DECODER_RESULT_AGAIN) {
            flow.onFrameReceiveResult(result)
            recycleOutputBuffer(outputBuffer)
            return
        }
        if (result == SOFTWARE_DECODER_RESULT_EOF) {
            flow.onFrameReceiveResult(result)
            skippedOutputBufferCount = addSkippedOutputBufferCount(
                skippedOutputBufferCount,
                flow.drainUnmatchedInputSamples(),
            )
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            queueOutputBuffer(outputBuffer)
            return
        }
        if (result != SOFTWARE_DECODER_RESULT_OK) {
            throw SoftwareVideoDecoderException("Unexpected " + codecName + " receive status (" + result + ")")
        }

        if (isFlushRequested()) {
            nativeDiscardFrame(handle)
            recycleOutputBuffer(outputBuffer)
            return
        }
        flow.onFrameProduced()

        val timeUs = resolveSoftwareFrameTimestampUs(frameInfo[0], frameInfo[1], lastPacketTimestampUs)
        val startTimeUs = synchronized(lock) { outputStartTimeUs }
        val currentOutputMode = outputMode
        if (isSoftwareFrameBeforeOutputStart(timeUs, startTimeUs) || !shouldConvertSoftwareFrame(currentOutputMode)) {
            // Keep FFmpeg's reference-picture state, but avoid allocating/converting frames that
            // cannot be presented before the seek target or while no output is attached.
            nativeDiscardFrame(handle)
            skippedOutputBufferCount = addSkippedOutputBufferCount(skippedOutputBufferCount, 1)
            recycleOutputBuffer(outputBuffer)
            flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
            return
        }

        val width = frameInfo[2].toInt()
        val height = frameInfo[3].toInt()
        if (usesSoftwarePrivateSurfaceFrame(currentOutputMode) && !supportsYv12SurfaceSize(width, height)) {
            nativeDiscardFrame(handle)
            throw SoftwareVideoDecoderException("YV12 surface output requires even frame dimensions")
        }
        val frameDataSpace = softwareFrameDataSpace(
            colorPrimaries = frameInfo[5].toInt(),
            colorTransfer = frameInfo[6].toInt(),
            colorRange = frameInfo[7].toInt(),
            matrixColorSpace = frameInfo[4].toInt(),
            frameHeight = height,
        )
        val yStride = align(width)
        val uvStride = align((width / 2) + (width % 2))
        outputBuffer.decoderPrivate = 0L
        outputBuffer.init(timeUs, currentOutputMode, null)
        if (usesSoftwarePrivateSurfaceFrame(currentOutputMode)) {
            // Keep the reference-counted FFmpeg frame alive until Media3 renders or drops it.
            // JNI converts directly into ANativeWindow's YV12 buffer, avoiding an intermediate
            // direct ByteBuffer allocation and a second full-frame copy.
            // A reused decoder output may still retain the previous GL/YUV direct buffer.
            // Drop all views before switching to the native-owned surface frame.
            clearSoftwareYuvOutputStorage(outputBuffer)
            outputBuffer.initForPrivateFrame(width, height)
            setPrivateSurfaceFrameDataSpace(outputBuffer, frameDataSpace)
            val reusableFrameHandle = synchronized(lock) { reusableFrameHandles.acquire() }
            // nativeTakeFrame consumes the reusable handle on both success and failure.
            val frameHandle = nativeTakeFrame(handle, reusableFrameHandle)
            if (frameHandle == 0L) {
                nativeDiscardFrame(handle)
                throw SoftwareVideoDecoderException("Failed to retain " + codecName + " output frame")
            }
            outputBuffer.decoderPrivate = frameHandle
        } else {
            if (!outputBuffer.initForYuvFrame(width, height, yStride, uvStride, softwareFrameColorSpace(frameInfo))) {
                nativeDiscardFrame(handle)
                throw SoftwareVideoDecoderException("Invalid " + codecName + " frame " + width + "x" + height)
            }
            val frameData = outputBuffer.data
                ?: throw SoftwareVideoDecoderException("Missing " + codecName + " frame buffer")
            val requiredBytes = requiredSoftwareYuv420BufferSize(width, height, yStride, uvStride)
                ?: throw SoftwareVideoDecoderException("Invalid " + codecName + " YUV buffer layout")
            if (!frameData.isDirect || frameData.capacity().toLong() < requiredBytes) {
                nativeDiscardFrame(handle)
                throw SoftwareVideoDecoderException("Insufficient " + codecName + " YUV frame buffer capacity")
            }
            if (!nativeConvertFrame(handle, frameData, yStride, uvStride)) {
                throw SoftwareVideoDecoderException("Failed to convert " + codecName + " frame")
            }
            setOutputFrameDataSpace(outputBuffer, frameDataSpace)
        }
        outputBuffer.format = format
        if (firstSamplePending) {
            outputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
            firstSamplePending = false
        }
        queueOutputBuffer(outputBuffer)
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
    }

    private fun queueOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        var notify = false
        synchronized(lock) {
            if (released || flushRequested) {
                recycleOutputBufferLocked(outputBuffer)
            } else {
                notify = queuedOutputBuffers.isEmpty()
                outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount
                skippedOutputBufferCount = 0
                queuedOutputBuffers.addLast(outputBuffer)
            }
            lock.notifyAll()
        }
        if (notify) dispatchOutputBufferAvailable()
    }

    private fun recycleOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        synchronized(lock) { recycleOutputBufferLocked(outputBuffer) }
    }

    private fun recycleOutputBufferLocked(outputBuffer: VideoDecoderOutputBuffer) {
        if (usesSoftwarePrivateSurfaceFrame(outputBuffer.mode)) {
            val frameHandle = takeSoftwarePrivateFrameHandle(outputBuffer)
            if (frameHandle != 0L && (released || !reusableFrameHandles.recycle(frameHandle))) {
                nativeReleaseFrame(frameHandle)
            }
        }
        outputBuffer.clear()
        if (!released) availableOutputBuffers.addLast(outputBuffer)
        lock.notifyAll()
    }

    private fun recycleInputBufferLocked(inputBuffer: DecoderInputBuffer) {
        inputBuffer.clear()
        if (!released) availableInputBuffers.addLast(inputBuffer)
    }

    private fun isFlushRequested(): Boolean = synchronized(lock) { flushRequested || released }

    private fun fail(error: SoftwareVideoDecoderException, ignoreIfFlushing: Boolean = true) {
        val notifyError = synchronized(lock) {
            val staleDecode = flushRequested && ignoreIfFlushing
            if (!staleDecode && exception == null) exception = error
            if (!staleDecode) flushRequested = false
            pendingInputBuffer?.let {
                recycleInputBufferLocked(it)
                pendingInputBuffer = null
            }
            if (!staleDecode) {
                while (queuedInputBuffers.isNotEmpty()) recycleInputBufferLocked(queuedInputBuffers.removeFirst())
                while (queuedOutputBuffers.isNotEmpty()) recycleOutputBufferLocked(queuedOutputBuffers.removeFirst())
                flow.reset()
            }
            lock.notifyAll()
            !staleDecode
        }
        if (notifyError) dispatchOutputBufferAvailable()
    }

    private fun processFlush() {
        val pending = pendingInputBuffer
        pendingInputBuffer = null
        flow.reset()
        skippedOutputBufferCount = 0
        firstSamplePending = false
        lastPacketTimestampUs = C.TIME_UNSET
        if (pending != null) synchronized(lock) { recycleInputBufferLocked(pending) }
        nativeFlush(handle)
        synchronized(lock) {
            exception = null
            flushRequested = false
            lock.notifyAll()
        }
        // Wake the renderer after the flush barrier: the worker may just have returned
        // the input buffer that was in flight when flush() was requested.
        dispatchInputBufferAvailable()
    }

    private fun maybeThrowExceptionLocked() {
        exception?.let { throw it }
    }

    private fun dispatchInputBufferAvailable() = dispatchCallback(input = true)

    private fun dispatchOutputBufferAvailable() = dispatchCallback(input = false)

    private fun dispatchCallback(input: Boolean) {
        val pair = synchronized(lock) {
            if (released || flushRequested) null else callback to callbackExecutor
        } ?: return
        val target = pair.first ?: return
        val executor = pair.second ?: return
        try {
            executor.execute {
                if (input) target.onInputBufferAvailable() else target.onOutputBufferAvailable()
            }
        } catch (_: RejectedExecutionException) {
            // Renderer is shutting down; its lifecycle will release this decoder.
        }
    }

    private data class Work(
        val action: SoftwareDecoderWorkAction,
        val outputBuffer: VideoDecoderOutputBuffer? = null,
    )

    companion object {
        private const val TAG = "SoftwareVideoDecoder"
        private const val INPUT_BUFFERS = 6
        private const val OUTPUT_BUFFERS = 6
        private const val STRIDE_ALIGNMENT = 64

        private val HIGH_BIT_DEPTH_AVC_PROFILES = setOf(44, 110, 122, 244)
        private const val HEVC_PROFILE_RANGE_EXTENSIONS = 4

        val isAvailable: Boolean by lazy {
            runCatching {
                System.loadLibrary("avutil")
                System.loadLibrary("swscale")
                System.loadLibrary("avcodec")
                System.loadLibrary("nuvio_swvideo")
                nativeLoad()
            }.onFailure { Log.w(TAG, "Software video decoding is unavailable", it) }.getOrDefault(false)
        }

        fun codecNameFor(mimeType: String?): String? =
            when (mimeType) {
                MimeTypes.VIDEO_H264 -> "h264"
                MimeTypes.VIDEO_H265 -> "hevc"
                MimeTypes.VIDEO_VC1 -> "vc1"
                else -> null
            }

        fun needsSoftwareDecoding(format: Format): Boolean =
            when (format.sampleMimeType) {
                MimeTypes.VIDEO_H264 -> avcProfile(format) in HIGH_BIT_DEPTH_AVC_PROFILES
                MimeTypes.VIDEO_H265 -> hevcProfile(format) == HEVC_PROFILE_RANGE_EXTENSIONS
                MimeTypes.VIDEO_VC1 -> true
                else -> false
            }

        private fun avcProfile(format: Format): Int? {
            format.codecs
                ?.split(',')
                ?.map(String::trim)
                ?.firstOrNull { it.startsWith("avc1.") || it.startsWith("avc3.") }
                ?.substringAfter('.')
                ?.take(2)
                ?.toIntOrNull(16)
                ?.let { return it }
            val sps = format.initializationData.firstOrNull() ?: return null
            var index = 0
            while (index < sps.size && sps[index].toInt() == 0) index++
            if (index >= sps.size || sps[index].toInt() != 1) index = -1
            val header = index + 1
            if (header + 1 >= sps.size || (sps[header].toInt() and 0x1F) != 7) return null
            return sps[header + 1].toInt() and 0xFF
        }

        private fun hevcProfile(format: Format): Int? =
            format.codecs
                ?.split(',')
                ?.map(String::trim)
                ?.firstOrNull { it.startsWith("hvc1.") || it.startsWith("hev1.") }
                ?.split('.')
                ?.getOrNull(1)
                ?.trimStart('A', 'B', 'C')
                ?.toIntOrNull()

        private fun extraData(format: Format): ByteArray? {
            if (format.initializationData.isEmpty()) return null
            val output = ByteArrayOutputStream()
            format.initializationData.forEach { output.write(it) }
            return output.toByteArray()
        }

        private fun align(value: Int): Int = (value + STRIDE_ALIGNMENT - 1) and (STRIDE_ALIGNMENT - 1).inv()

        @JvmStatic private external fun nativeLoad(): Boolean
        @JvmStatic private external fun nativeCreate(codecName: String, extraData: ByteArray?, threads: Int): Long
        @JvmStatic private external fun nativeFlush(handle: Long)
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativeSendPacket(handle: Long, data: ByteBuffer, size: Int, timeUs: Long): Int
        @JvmStatic private external fun nativeSendEndOfStream(handle: Long): Int
        @JvmStatic private external fun nativeReceiveFrame(handle: Long, info: LongArray): Int
        @JvmStatic private external fun nativeTakeFrame(handle: Long, reusableFrameHandle: Long): Long
        @JvmStatic private external fun nativeReleaseFrame(frameHandle: Long)
        @JvmStatic private external fun nativeDiscardFrame(handle: Long)
        @JvmStatic private external fun nativeConvertFrame(handle: Long, output: ByteBuffer, yStride: Int, uvStride: Int): Boolean
        @JvmStatic private external fun nativeRenderPrivateFrame(handle: Long, frameHandle: Long, surface: Surface, dataSpace: Int): Int
    }
}
