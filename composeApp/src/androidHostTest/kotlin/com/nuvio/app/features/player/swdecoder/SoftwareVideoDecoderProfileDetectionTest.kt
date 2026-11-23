@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.swdecoder

import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoftwareVideoDecoderProfileDetectionTest {
    @Test
    fun explicitTenBitAvcColorInfoSelectsSoftwareFallbackWithoutCodecString() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun softwareFallbackReasonIdentifiesAvcColorBitDepth() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()

        assertTrue(SoftwareVideoDecoder.softwareDecodingReason(format) == "avc-color-info-bit-depth")
    }

    @Test
    fun explicitEightBitAvcColorInfoDoesNotSelectSoftwareFallback() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.640028")
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(8).setChromaBitdepth(8).build())
            .build()

        assertFalse(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun twelveBitHevcColorInfoSelectsSoftwareFallback() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L120.B0")
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(12).setChromaBitdepth(12).build())
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun softwareFallbackReasonIdentifiesTwelveBitHevc() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(12).setChromaBitdepth(12).build())
            .build()

        assertTrue(SoftwareVideoDecoder.softwareDecodingReason(format) == "hevc-12-bit")
    }

    @Test
    fun tenBitHevcMain10KeepsTheHardwareDecoderPath() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L120.B0")
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()

        assertFalse(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun malformedAvcCodecStringFallsBackToTheSpsProfile() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.invalid")
            .setInitializationData(listOf(byteArrayOf(0, 0, 0, 1, 0x67, 0x6E, 0x00, 0x20)))
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun avcSpsSearchContinuesAcrossInitializationDataEntries() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setInitializationData(
                listOf(
                    byteArrayOf(0x00),
                    byteArrayOf(0, 0, 0, 1, 0x67, 0x6E, 0x00, 0x20),
                ),
            )
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun malformedFirstAvcCodecEntryDoesNotHideLaterValidProfile() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.invalid,avc3.6E0032")
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }

    @Test
    fun malformedFirstHevcCodecEntryDoesNotHideRangeExtensionProfile() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.invalid,hev1.4.4.L120.B0")
            .build()

        assertTrue(SoftwareVideoDecoder.needsSoftwareDecoding(format))
    }
}
