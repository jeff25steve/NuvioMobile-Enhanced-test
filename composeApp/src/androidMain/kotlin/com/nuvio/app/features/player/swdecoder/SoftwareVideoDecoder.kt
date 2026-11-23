package com.nuvio.app.features.player.swdecoder

import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal const val FFMPEG_INPUT_PADDING_SIZE = 64

internal fun softwareFrameColorSpace(frameInfo: LongArray): Int = frameInfo[4].toInt()

internal fun resolveSoftwareFrameTimestampUs(ptsUs: Long, bestEffortTimestampUs: Long, packetTimestampUs: Long): Long =
    when {
        ptsUs != Long.MIN_VALUE -> ptsUs
        bestEffortTimestampUs != Long.MIN_VALUE -> bestEffortTimestampUs
        else -> packetTimestampUs
    }

internal fun setOutputFrameDataSpace(outputBuffer: VideoDecoderOutputBuffer, dataSpace: Int) {
    outputBuffer.decoderPrivate = dataSpace.toLong()
}

internal fun outputFrameDataSpace(outputBuffer: VideoDecoderOutputBuffer): Int =
    outputBuffer.decoderPrivate.toInt()

/** Returns the byte capacity required by a tightly described I420 frame, or null for invalid layouts. */
internal fun supportsYv12SurfaceSize(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0

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
    format: Format,
    threads: Int,
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, SoftwareVideoDecoderException>(
    emptyBuffers<DecoderInputBuffer>(INPUT_BUFFERS),
    emptyBuffers<VideoDecoderOutputBuffer>(OUTPUT_BUFFERS),
) {
    private val codecName: String
    private var handle = 0L
    private val frameInfo = LongArray(6)

    @Volatile
    var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    init {
        val name = codecNameFor(format.sampleMimeType)
        handle = if (name != null && isAvailable) nativeCreate(name, extraData(format), threads) else 0L
        if (handle == 0L) {
            release()
            throw SoftwareVideoDecoderException("Failed to initialize software decoder for ${format.sampleMimeType}")
        }
        codecName = name!!
        setInitialInputBufferSize(
            if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_BUFFER_SIZE,
        )
    }

    override fun getName(): String = "nuvio-sw-$codecName"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FFMPEG_INPUT_PADDING_SIZE)

    override fun createOutputBuffer(): VideoDecoderOutputBuffer =
        VideoDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): SoftwareVideoDecoderException =
        SoftwareVideoDecoderException("Unexpected software decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean,
    ): SoftwareVideoDecoderException? {
        if (reset) nativeFlush(handle)
        val data = inputBuffer.data ?: return null
        val sampleSize = prepareFfmpegPacketData(data)
        if (nativeSendPacket(handle, data, sampleSize, inputBuffer.timeUs) == RESULT_ERROR) {
            return SoftwareVideoDecoderException("Failed to decode $codecName sample")
        }
        when (nativeReceiveFrame(handle, frameInfo)) {
            RESULT_ERROR -> return SoftwareVideoDecoderException("Failed to receive $codecName frame")
            RESULT_SKIP -> {
                outputBuffer.shouldBeSkipped = true
                return null
            }
        }
        val timeUs = resolveSoftwareFrameTimestampUs(frameInfo[0], frameInfo[1], inputBuffer.timeUs)
        if (!isAtLeastOutputStartTimeUs(timeUs)) {
            outputBuffer.shouldBeSkipped = true
            return null
        }
        outputBuffer.shouldBeSkipped = false
        outputBuffer.init(timeUs, outputMode, null)
        val width = frameInfo[2].toInt()
        val height = frameInfo[3].toInt()
        val yStride = align(width)
        val uvStride = align((width + 1) / 2)
        if (!outputBuffer.initForYuvFrame(width, height, yStride, uvStride, softwareFrameColorSpace(frameInfo))) {
            return SoftwareVideoDecoderException("Invalid $codecName frame ${width}x$height")
        }
        val frameData = outputBuffer.data
            ?: return SoftwareVideoDecoderException("Missing $codecName frame buffer")
        val requiredBytes = requiredSoftwareYuv420BufferSize(width, height, yStride, uvStride)
            ?: return SoftwareVideoDecoderException("Invalid $codecName YUV buffer layout")
        if (!frameData.isDirect || frameData.capacity().toLong() < requiredBytes) {
            return SoftwareVideoDecoderException("Insufficient $codecName YUV frame buffer capacity")
        }
        if (!nativeConvertFrame(handle, frameData, yStride, uvStride)) {
            return SoftwareVideoDecoderException("Failed to convert $codecName frame")
        }
        setOutputFrameDataSpace(outputBuffer, frameInfo[5].toInt())
        outputBuffer.format = inputBuffer.format
        return null
    }

    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val data = outputBuffer.data
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV || data == null) {
            throw SoftwareVideoDecoderException("Invalid output buffer")
        }
        if (!supportsYv12SurfaceSize(outputBuffer.width, outputBuffer.height)) {
            throw SoftwareVideoDecoderException("YV12 surface output requires positive even frame dimensions")
        }
        val requiredBytes = requiredSoftwareYuv420BufferSize(
            outputBuffer.width,
            outputBuffer.height,
            outputBuffer.yStride,
            outputBuffer.uvStride,
        ) ?: throw SoftwareVideoDecoderException("Invalid output frame layout")
        if (!data.isDirect || data.capacity().toLong() < requiredBytes) {
            throw SoftwareVideoDecoderException("Insufficient output frame buffer capacity")
        }
        val result = nativeRender(
            handle,
            surface,
            data,
            outputBuffer.width,
            outputBuffer.height,
            outputBuffer.yStride,
            outputBuffer.uvStride,
            outputFrameDataSpace(outputBuffer),
        )
        if (result == RESULT_ERROR) throw SoftwareVideoDecoderException("Failed to render $codecName frame")
    }

    override fun release() {
        super.release()
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "SoftwareVideoDecoder"
        private const val INPUT_BUFFERS = 6
        private const val OUTPUT_BUFFERS = 6
        private const val DEFAULT_INPUT_BUFFER_SIZE = 768 * 1024
        private const val STRIDE_ALIGNMENT = 64
        private const val RESULT_SKIP = 1
        private const val RESULT_ERROR = -1

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

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> emptyBuffers(size: Int): Array<T> = arrayOfNulls<T>(size) as Array<T>

        private fun align(value: Int): Int = (value + STRIDE_ALIGNMENT - 1) and (STRIDE_ALIGNMENT - 1).inv()

        @JvmStatic
        private external fun nativeLoad(): Boolean

        @JvmStatic
        private external fun nativeCreate(codecName: String, extraData: ByteArray?, threads: Int): Long

        @JvmStatic
        private external fun nativeFlush(handle: Long)

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativeSendPacket(handle: Long, data: ByteBuffer, size: Int, timeUs: Long): Int

        @JvmStatic
        private external fun nativeReceiveFrame(handle: Long, info: LongArray): Int

        @JvmStatic
        private external fun nativeConvertFrame(handle: Long, output: ByteBuffer, yStride: Int, uvStride: Int): Boolean

        @JvmStatic
        private external fun nativeRender(
            handle: Long,
            surface: Surface,
            source: ByteBuffer,
            width: Int,
            height: Int,
            yStride: Int,
            uvStride: Int,
            dataSpace: Int,
        ): Int
    }
}
