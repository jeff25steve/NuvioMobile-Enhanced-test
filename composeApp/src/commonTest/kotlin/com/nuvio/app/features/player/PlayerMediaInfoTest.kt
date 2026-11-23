package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerMediaInfoTest {
    @Test
    fun mpvReportsPqAsPqAndPreservesDecodedBitDepth() {
        val properties = mapOf(
            "current-tracks/video/codec" to "hevc",
            "video-params/gamma" to "pq",
            "video-params/colormatrix" to "bt.2020-ncl",
            "video-params/primaries" to "bt.2020",
            "video-params/pixelformat" to "yuv420p10le",
        )

        val info = mpvMediaInfo { properties[it] }

        assertEquals(listOf("HDR (PQ)"), info.hdr)
        assertEquals(10, info.bitDepth)
    }

    @Test
    fun mpvReportsDolbyVisionAndItsPqBaseLayerWithoutMisclassifyingIt() {
        val properties = mapOf(
            "current-tracks/video/codec" to "hevc",
            "current-tracks/video/dolby-vision-profile" to "Profile 8.1",
            "video-params/gamma" to "pq",
            "video-params/colormatrix" to "bt.2020-ncl",
        )

        val info = mpvMediaInfo { properties[it] }

        assertEquals(listOf("DV", "HDR (PQ)"), info.hdr)
    }

    @Test
    fun bt2020PrimariesWithSdrTransferDoNotProduceAnHdrBadge() {
        val properties = mapOf(
            "current-tracks/video/codec" to "hevc",
            "video-params/gamma" to "bt.1886",
            "video-params/colormatrix" to "bt.2020-ncl",
            "video-params/primaries" to "bt.2020",
        )

        assertEquals(emptyList(), mpvMediaInfo { properties[it] }.hdr)
    }

    @Test
    fun mpvBitDepthRecognizesCommonPlanarSemiPlanarAndPackedFormats() {
        val formats = listOf(
            "yuv420p" to 8,
            "nv12" to 8,
            "yuv420p9le" to 9,
            "yuv420p10le" to 10,
            "p010le" to 10,
            "yuv420p12le" to 12,
            "xyz12le" to 12,
            "yuv420p14le" to 14,
            "yuv420p16le" to 16,
            "p016le" to 16,
            "rgb48le" to 16,
            "rgba64le" to 16,
        )
        formats.forEach { (pixelFormat, expectedDepth) ->
            assertEquals(expectedDepth, mpvBitDepth(pixelFormat), pixelFormat)
        }
    }

    @Test
    fun mpvLeavesUnknownAndHardwareOnlyPixelFormatsUnclassified() {
        listOf("cuda", "mediacodec", "vulkan", "unknown-format").forEach { pixelFormat ->
            assertEquals(null, mpvBitDepth(pixelFormat), pixelFormat)
        }
    }

    @Test
    fun estimatedOutputFpsMustBeFiniteAndPositive() {
        assertEquals(23.976, mpvPlaybackEstimatedFps(23.976))
        assertEquals(null, mpvPlaybackEstimatedFps(null))
        assertEquals(null, mpvPlaybackEstimatedFps(0.0))
        assertEquals(null, mpvPlaybackEstimatedFps(-1.0))
        assertEquals(null, mpvPlaybackEstimatedFps(Double.NaN))
        assertEquals(null, mpvPlaybackEstimatedFps(Double.POSITIVE_INFINITY))
    }
}
