@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFormatMetadataTest {
    @Test
    fun exoPlayerRecognizesAllDolbyVisionCodecFamiliesWithFallbackMimeTypes() {
        val codecs = listOf(
            "dvhe.08.06",
            "dvh1.08.06",
            "dvav.09.06",
            "dva1.09.06",
            "dav1.10.09",
        )

        codecs.forEach { codec ->
            assertEquals(
                "dolby_vision",
                exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, codec, C.COLOR_TRANSFER_ST2084),
                "Codec $codec must remain identified as Dolby Vision when the MIME type falls back",
            )
        }
        assertEquals(
            "dolby_vision",
            exoPlaybackHdrFormat(MimeTypes.VIDEO_DOLBY_VISION, null, Format.NO_VALUE),
        )
    }

    @Test
    fun exoPlayerMapsTransferFunctionsWithoutOverstatingPqAsHdr10() {
        assertEquals("hdr", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_ST2084))
        assertEquals(listOf("HDR (PQ)"), exoPlaybackHdrBadges(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_ST2084))
        assertEquals(
            listOf("DV", "HDR (PQ)"),
            exoPlaybackHdrBadges(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", C.COLOR_TRANSFER_ST2084),
        )
        assertEquals("hlg", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_HLG))
        assertEquals("sdr", exoPlaybackHdrFormat(MimeTypes.VIDEO_H264, "avc1.640028", C.COLOR_TRANSFER_SDR))
        assertEquals("", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.1.6.L120.B0", Format.NO_VALUE))
    }

    @Test
    fun dolbyVisionProfileIsExtractedForHevcAvcAndAv1Codecs() {
        assertEquals("Profile 8", exoDolbyVisionProfile("dvhe.08.06"))
        assertEquals("Profile 8", exoDolbyVisionProfile("dvh1.08.06"))
        assertEquals("Profile 9", exoDolbyVisionProfile("dvav.09.06"))
        assertEquals("Profile 9", exoDolbyVisionProfile("dva1.09.06"))
        assertEquals("Profile 10", exoDolbyVisionProfile("dav1.10.09"))
        assertEquals("", exoDolbyVisionProfile("hvc1.2.4.L153.B0"))
    }

    @Test
    fun mpvUsesDolbyVisionSignalsAndTransferFunctionNotBt2020PrimariesAlone() {
        assertEquals(
            "dolby_vision",
            mpvPlaybackHdrFormat(
                dvProfile = null,
                videoCodec = "hevc",
                videoDecoder = "hardware decoder",
                codecProfile = "",
                gamma = "pq",
                colorMatrix = "dolbyvision",
            ),
        )
        assertEquals(
            "dolby_vision",
            mpvPlaybackHdrFormat(null, "hevc", "hardware decoder", "Profile 8.1", "pq", "bt.2020-ncl"),
        )
        listOf("dvhe.08.06", "dvh1.08.06", "dvav.09.06", "dva1.09.06", "dav1.10.09").forEach { codec ->
            assertEquals(
                "dolby_vision",
                mpvPlaybackHdrFormat(null, codec, "hardware decoder", "", "pq", "bt.2020-ncl"),
            )
        }
        assertEquals(
            "hdr",
            mpvPlaybackHdrFormat(null, "hevc", "hardware decoder", "", "pq", "bt.2020-ncl"),
        )
        assertEquals(
            "hlg",
            mpvPlaybackHdrFormat(null, "hevc", "hardware decoder", "", "hlg", "bt.2020-ncl"),
        )
        assertEquals(
            "sdr",
            mpvPlaybackHdrFormat(null, "hevc", "hardware decoder", "", "sdr", "bt.2020-ncl"),
        )
        assertEquals(
            "",
            mpvPlaybackHdrFormat(null, "hevc", "hardware decoder", "", "bt.1886", "bt.2020-ncl"),
        )
    }

    @Test
    fun mpvMediaInfoSeparatesSourceAndPostFilterColorPipelineMetadata() {
        val properties = mapOf(
            "current-tracks/video/codec" to "hevc",
            "current-tracks/video/decoder-desc" to "MediaCodec",
            "video-params/primaries" to "bt.2020",
            "video-params/gamma" to "pq",
            "video-params/colormatrix" to "bt.2020-ncl",
            "video-params/colorlevels" to "limited",
            "video-params/pixelformat" to "yuv420p10le",
            "video-out-params/w" to "1920",
            "video-out-params/h" to "1080",
            "video-out-params/primaries" to "bt.709",
            "video-out-params/gamma" to "bt.1886",
            "video-out-params/colormatrix" to "bt.709",
            "video-out-params/colorlevels" to "limited",
            "video-out-params/pixelformat" to "yuv420p",
        )
        val info = mpvMediaInfo(properties::get)
        assertEquals("bt.2020", info.videoColorPrimaries)
        assertEquals("pq", info.videoTransferFunction)
        assertEquals("bt.2020-ncl", info.videoColorMatrix)
        assertEquals("Limited", info.videoColorRange)
        assertEquals(1920, info.videoOutputWidth)
        assertEquals(1080, info.videoOutputHeight)
        assertEquals("yuv420p", info.videoOutputPixelFormat)
        assertEquals(8, info.videoOutputBitDepth)
        assertEquals("bt.709", info.videoOutputColorPrimaries)
        assertEquals("bt.1886", info.videoOutputTransferFunction)
        assertEquals("bt.709", info.videoOutputColorMatrix)
        assertEquals("Limited", info.videoOutputColorRange)
    }

    @Test
    fun mpvMediaInfoLeavesUnknownPostFilterMetadataUnset() {
        val info = mpvMediaInfo { null }
        assertEquals(null, info.videoOutputWidth)
        assertEquals(null, info.videoOutputHeight)
        assertEquals(null, info.videoOutputPixelFormat)
        assertEquals(null, info.videoOutputBitDepth)
        assertEquals(null, info.videoOutputColorPrimaries)
        assertEquals(null, info.videoOutputTransferFunction)
        assertEquals(null, info.videoOutputColorMatrix)
        assertEquals(null, info.videoOutputColorRange)
    }

    @Test
    fun colorRangeAndBitDepthAreOnlyReportedWhenKnown() {
        assertEquals("Full", exoPlaybackColorRange(C.COLOR_RANGE_FULL))
        assertEquals("Limited", exoPlaybackColorRange(C.COLOR_RANGE_LIMITED))
        assertEquals("", exoPlaybackColorRange(Format.NO_VALUE))

        assertEquals("Full", mpvPlaybackColorRange("full"))
        assertEquals("Limited", mpvPlaybackColorRange("limited"))
        assertEquals(null, mpvPlaybackColorRange("auto"))
        assertEquals(null, mpvPlaybackColorRange(null))

        assertEquals(12, mpvBitDepth("yuv420p12le"))
        assertEquals(10, mpvBitDepth("yuv420p10le"))
        assertEquals(8, mpvBitDepth("yuv420p"))
        assertEquals(9, mpvBitDepth("yuv420p9le"))
        assertEquals(14, mpvBitDepth("yuv420p14le"))
        assertEquals(16, mpvBitDepth("yuv420p16le"))
        assertEquals(10, mpvBitDepth("p010le"))
        assertEquals(null, mpvBitDepth("cuda"))
    }
}
