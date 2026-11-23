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
}
