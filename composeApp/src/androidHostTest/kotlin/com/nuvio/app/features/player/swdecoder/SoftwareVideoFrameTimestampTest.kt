package com.nuvio.app.features.player.swdecoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoftwareVideoFrameTimestampTest {
    @Test
    fun readsColorSpaceFromTheFrameInfoColorSpaceSlotNotTheHeightSlot() {
        val frameInfo = longArrayOf(1_000_000L, 1_000_000L, 1920L, 1080L, 2L, 0x01234567L)

        assertEquals(2, softwareFrameColorSpace(frameInfo))
    }

    @Test
    fun prefersDecodedPresentationTimestamp() {
        assertEquals(1_250_000L, resolveSoftwareFrameTimestampUs(1_250_000L, 1_200_000L, 1_300_000L))
    }

    @Test
    fun usesBestEffortTimestampWhenDecodedPtsIsMissing() {
        assertEquals(1_200_000L, resolveSoftwareFrameTimestampUs(Long.MIN_VALUE, 1_200_000L, 1_300_000L))
    }

    @Test
    fun fallsBackToPacketTimestampWhenBothFrameTimestampsAreMissing() {
        assertEquals(1_300_000L, resolveSoftwareFrameTimestampUs(Long.MIN_VALUE, Long.MIN_VALUE, 1_300_000L))
    }

    @Test
    fun privateNativeFrameOutputIsRestrictedToSurfaceYuvMode() {
        assertEquals(true, usesSoftwarePrivateSurfaceFrame(androidx.media3.common.C.VIDEO_OUTPUT_MODE_SURFACE_YUV))
        assertEquals(false, usesSoftwarePrivateSurfaceFrame(androidx.media3.common.C.VIDEO_OUTPUT_MODE_YUV))
        assertEquals(false, usesSoftwarePrivateSurfaceFrame(androidx.media3.common.C.VIDEO_OUTPUT_MODE_NONE))
    }

    @Test
    fun nativePrivateFrameOwnershipCanOnlyBeTransferredOnce() {
        val output = VideoDecoderOutputBuffer(object : VideoDecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {
            override fun releaseOutputBuffer(buffer: VideoDecoderOutputBuffer) = Unit
        })
        output.decoderPrivate = 0x1234L

        assertEquals(0x1234L, takeSoftwarePrivateFrameHandle(output))
        assertEquals(0L, takeSoftwarePrivateFrameHandle(output))
    }

    @Test
    fun switchingToPrivateSurfaceFramesReleasesPreviousYuvStorage() {
        val output = VideoDecoderOutputBuffer(object : VideoDecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {
            override fun releaseOutputBuffer(buffer: VideoDecoderOutputBuffer) = Unit
        })
        output.data = java.nio.ByteBuffer.allocateDirect(128)
        output.yuvPlanes = arrayOf(
            java.nio.ByteBuffer.allocateDirect(32),
            java.nio.ByteBuffer.allocateDirect(16),
            java.nio.ByteBuffer.allocateDirect(16),
        )
        output.yuvStrides = intArrayOf(16, 8, 8)

        clearSoftwareYuvOutputStorage(output)

        assertNull(output.data)
        assertNull(output.yuvPlanes)
        assertNull(output.yuvStrides)
    }

    @Test
    fun requiresEvenDimensionsForYv12SurfaceOutput() {
        assertEquals(true, supportsYv12SurfaceSize(1920, 1080))
        assertEquals(false, supportsYv12SurfaceSize(1919, 1080))
        assertEquals(false, supportsYv12SurfaceSize(1920, 1079))
        assertEquals(false, supportsYv12SurfaceSize(0, 1080))
    }

    @Test
    fun computesRequiredYuv420BufferSizeForOddDimensionsAndAlignedStrides() {
        assertEquals(64L * 3L + 64L * 2L * 2L, requiredSoftwareYuv420BufferSize(3, 3, 64, 64))
    }

    @Test
    fun rejectsInvalidYuv420DimensionsAndStrides() {
        assertEquals(null, requiredSoftwareYuv420BufferSize(0, 3, 64, 64))
        assertEquals(null, requiredSoftwareYuv420BufferSize(3, 3, 2, 64))
        assertEquals(null, requiredSoftwareYuv420BufferSize(3, 3, 64, 1))
    }

    @Test
    fun rejectsYuv420BuffersLargerThanByteBufferCapacityCanRepresent() {
        assertEquals(null, requiredSoftwareYuv420BufferSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
