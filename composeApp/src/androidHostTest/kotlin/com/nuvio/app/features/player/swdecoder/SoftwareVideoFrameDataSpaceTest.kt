@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.swdecoder

import androidx.media3.decoder.VideoDecoderOutputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class SoftwareVideoFrameDataSpaceTest {
    @Test
    fun queuedFramesRetainTheirOwnDataSpace() {
        val firstFrame = VideoDecoderOutputBuffer { }
        val secondFrame = VideoDecoderOutputBuffer { }

        setOutputFrameDataSpace(firstFrame, 0x00123456)
        setOutputFrameDataSpace(secondFrame, 0x00654321)

        assertEquals(0x00123456, outputFrameDataSpace(firstFrame))
        assertEquals(0x00654321, outputFrameDataSpace(secondFrame))
        assertEquals(0x00123456, outputFrameDataSpace(firstFrame))
    }

    @Test
    fun displayPrimariesAreIndependentFromYuvMatrixCoefficients() {
        val dataSpace = softwareFrameDataSpace(
            colorPrimaries = 9, // AVCOL_PRI_BT2020
            colorTransfer = 1, // AVCOL_TRC_BT709
            colorRange = 1, // AVCOL_RANGE_MPEG
            matrixColorSpace = 2, // BT.709 matrix
            frameHeight = 1080,
        )

        assertEquals((6 shl 16) or (3 shl 22) or (2 shl 27), dataSpace)
    }

    @Test
    fun explicitBt601PrimariesOverrideResolutionFallback() {
        val pal = softwareFrameDataSpace(
            colorPrimaries = 5, // AVCOL_PRI_BT470BG
            colorTransfer = 6,
            colorRange = 1,
            matrixColorSpace = 2,
            frameHeight = 480,
        )
        val ntsc = softwareFrameDataSpace(
            colorPrimaries = 6, // AVCOL_PRI_SMPTE170M
            colorTransfer = 6,
            colorRange = 1,
            matrixColorSpace = 2,
            frameHeight = 576,
        )

        assertEquals((2 shl 16) or (3 shl 22) or (2 shl 27), pal)
        assertEquals((4 shl 16) or (3 shl 22) or (2 shl 27), ntsc)
    }

    @Test
    fun mapsFilmAndDciP3Primaries() {
        val film = softwareFrameDataSpace(8, 1, 1, 2, 1080)
        val dciP3 = softwareFrameDataSpace(11, 1, 1, 2, 1080)

        assertEquals((9 shl 16) or (3 shl 22) or (2 shl 27), film)
        assertEquals((10 shl 16) or (3 shl 22) or (2 shl 27), dciP3)
    }

    @Test
    fun mapsTransferCharacteristicsWithoutCollapsingThemToSdr() {
        val linear = softwareFrameDataSpace(9, 8, 1, 3, 1080)
        val srgb = softwareFrameDataSpace(9, 13, 1, 3, 1080)
        val gamma22 = softwareFrameDataSpace(4, 4, 1, 1, 576)
        val gamma28 = softwareFrameDataSpace(5, 5, 1, 1, 576)
        val gamma26 = softwareFrameDataSpace(11, 17, 1, 2, 1080)
        val pq = softwareFrameDataSpace(9, 16, 1, 3, 1080)
        val hlg = softwareFrameDataSpace(9, 18, 1, 3, 1080)

        assertEquals((6 shl 16) or (1 shl 22) or (2 shl 27), linear)
        assertEquals((6 shl 16) or (2 shl 22) or (2 shl 27), srgb)
        assertEquals((8 shl 16) or (4 shl 22) or (2 shl 27), gamma22)
        assertEquals((2 shl 16) or (6 shl 22) or (2 shl 27), gamma28)
        assertEquals((10 shl 16) or (5 shl 22) or (2 shl 27), gamma26)
        assertEquals((6 shl 16) or (7 shl 22) or (2 shl 27), pq)
        assertEquals((6 shl 16) or (8 shl 22) or (2 shl 27), hlg)
    }

    @Test
    fun fullAndLimitedRangesArePreserved() {
        val full = softwareFrameDataSpace(9, 16, 2, 3, 1080)
        val limited = softwareFrameDataSpace(9, 16, 1, 3, 1080)

        assertEquals((6 shl 16) or (7 shl 22) or (1 shl 27), full)
        assertEquals((6 shl 16) or (7 shl 22) or (2 shl 27), limited)
    }

    @Test
    fun unspecifiedPrimariesFallBackToTheDecodedMatrixAndHeight() {
        val bt2020 = softwareFrameDataSpace(2, 2, 0, 3, 1080)
        val pal = softwareFrameDataSpace(2, 2, 0, 1, 576)
        val ntsc = softwareFrameDataSpace(2, 2, 0, 1, 480)

        assertEquals((6 shl 16) or (3 shl 22) or (2 shl 27), bt2020)
        assertEquals((2 shl 16) or (3 shl 22) or (2 shl 27), pal)
        assertEquals((4 shl 16) or (3 shl 22) or (2 shl 27), ntsc)
    }

    @Test
    fun privateSurfaceFramesKeepTheirDataspaceAlongsideTheNativeFrameHandle() {
        val output = VideoDecoderOutputBuffer { }
        output.decoderPrivate = 1234L

        setPrivateSurfaceFrameDataSpace(output, 0x12345678)

        assertEquals(0x12345678, privateSurfaceFrameDataSpace(output))
        assertEquals(1234L, output.decoderPrivate)
    }
}
