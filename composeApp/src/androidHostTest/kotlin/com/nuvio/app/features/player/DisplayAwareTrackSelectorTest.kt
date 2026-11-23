@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayAwareTrackSelectorTest {
    private val primaryDecoderSupport = RendererCapabilities.create(C.FORMAT_HANDLED)
    private val fallbackMimeDecoderSupport = RendererCapabilities.create(
        C.FORMAT_HANDLED,
        RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
        RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED,
        RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE,
    )

    @Test
    fun forceLowestBitrateBypassesDisplayHdrFiltering() {
        assertFalse(shouldApplyDisplayHdrFiltering(emptySet(), forceLowestBitrate = true))
    }

    @Test
    fun displayHdrFilteringRequiresKnownCapabilities() {
        assertFalse(shouldApplyDisplayHdrFiltering(null, forceLowestBitrate = false))
        assertTrue(shouldApplyDisplayHdrFiltering(emptySet(), forceLowestBitrate = false))
    }

    @Test
    fun missingHdrCapabilitiesOnAnAvailableDisplayMeansSdrOnly() {
        val hdrTypes = displayHdrTypesFromCapabilities(null)
        assertTrue(hdrTypes.isEmpty())
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                primaryDecoderSupport,
                hdrTypes,
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun reportedHdrTypesArePreserved() {
        assertTrue(
            displayHdrTypesFromCapabilities(
                intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
            ).contains(Display.HdrCapabilities.HDR_TYPE_HDR10),
        )
    }

    @Test
    fun masksPqWhenDisplayCannotPresentHdr10AndSdrFallbackExists() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                rendererSupportFlags = primaryDecoderSupport,
                supportedHdrTypes = emptySet(),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun preservesPqWhenDisplaySupportsHdr10() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun masksHlgWhenDisplayDoesNotReportHlgSupport() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG),
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun preservesHdrOnlyStreamsWhenNoSdrFallbackExists() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                primaryDecoderSupport,
                emptySet(),
                hasSdrFallback = false,
            ),
        )
    }

    @Test
    fun preservesHdrWhenDisplayCapabilitiesAreUnknown() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                primaryDecoderSupport,
                supportedHdrTypes = null,
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun dolbyVisionPrimaryDecoderRequiresDolbyVisionDisplaySupport() {
        val dolbyVision = videoFormat(
            mimeType = MimeTypes.VIDEO_DOLBY_VISION,
            transfer = C.COLOR_TRANSFER_ST2084,
            codecs = "dvhe.08.06",
        )
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                dolbyVision,
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                dolbyVision,
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun dolbyVisionFallbackDecoderUsesTheBaseLayerTransferType() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(
                    mimeType = MimeTypes.VIDEO_DOLBY_VISION,
                    transfer = C.COLOR_TRANSFER_ST2084,
                    codecs = "dvhe.08.06",
                ),
                fallbackMimeDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun hdr10PlusAloneDoesNotSatisfyPqBaseLayerSupport() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun preservesFormatsTheRendererCannotDecodeWithoutHdrFallbackLogic() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                C.FORMAT_UNSUPPORTED_TYPE,
                emptySet(),
                hasSdrFallback = true,
            ),
        )
    }

    private fun videoFormat(
        mimeType: String,
        transfer: Int,
        codecs: String? = null,
    ): Format {
        val builder = Format.Builder()
            .setSampleMimeType(mimeType)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
        if (codecs != null) builder.setCodecs(codecs)
        return builder.build()
    }
}
