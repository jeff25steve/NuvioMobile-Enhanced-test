@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
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
            exoPlaybackHdrFormat(MimeTypes.VIDEO_DOLBY_VISION, null, C.COLOR_TRANSFER_UNKNOWN),
        )
    }

    @Test
    fun exoPlayerMapsTransferFunctionsWithoutOverstatingPqAsHdr10() {
        assertEquals("hdr", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_ST2084))
        assertEquals("hlg", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_HLG))
        assertEquals("sdr", exoPlaybackHdrFormat(MimeTypes.VIDEO_H264, "avc1.640028", C.COLOR_TRANSFER_SDR))
        assertEquals("", exoPlaybackHdrFormat(MimeTypes.VIDEO_H265, "hvc1.1.6.L120.B0", C.COLOR_TRANSFER_UNKNOWN))
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
    fun colorRangeAndBitDepthAreOnlyReportedWhenKnown() {
        assertEquals("Full", exoPlaybackColorRange(C.COLOR_RANGE_FULL))
        assertEquals("Limited", exoPlaybackColorRange(C.COLOR_RANGE_LIMITED))
        assertEquals("", exoPlaybackColorRange(C.COLOR_RANGE_UNSPECIFIED))

        assertEquals("Full", mpvPlaybackColorRange("full"))
        assertEquals("Limited", mpvPlaybackColorRange("limited"))
        assertEquals(null, mpvPlaybackColorRange("auto"))
        assertEquals(null, mpvPlaybackColorRange(null))

        assertEquals(12, mpvBitDepth("yuv420p12le"))
        assertEquals(10, mpvBitDepth("yuv420p10le"))
        assertEquals(null, mpvBitDepth("yuv420p"))
    }
}
