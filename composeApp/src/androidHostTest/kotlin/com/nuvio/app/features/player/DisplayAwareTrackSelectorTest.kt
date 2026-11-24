@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayAwareTrackSelectorTest {
    private val primaryDecoderSupport = RendererCapabilities.create(C.FORMAT_HANDLED)
    private val exceedsCapabilitiesSupport = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
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
        val hdrTypes = displayHdrTypesFromCapabilities(null).orEmpty()
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
            ).orEmpty().contains(Display.HdrCapabilities.HDR_TYPE_HDR10),
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
    fun formatExceedingRendererCapabilitiesIsNotConsideredSupported() {
        assertFalse(isRendererFormatSupported(exceedsCapabilitiesSupport))
        assertTrue(isRendererFormatSupported(primaryDecoderSupport))
    }

    @Test
    fun sdrFormatExceedingRendererCapabilitiesIsNotAcceptedAsAnHdrFallback() {
        val sdr = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertFalse(isSupportedSdrFallback(sdr, exceedsCapabilitiesSupport))
        assertTrue(isSupportedSdrFallback(sdr, primaryDecoderSupport))
    }

    @Test
    fun hdrFormatExceedingRendererCapabilitiesIsNotMaskedAsAPlayableTrack() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
                exceedsCapabilitiesSupport,
                emptySet(),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun preservesHlgWhenDisplayReportsHlgSupport() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG),
                primaryDecoderSupport,
                setOf(Display.HdrCapabilities.HDR_TYPE_HLG),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun dolbyVisionFallbackMimeCodecUsesItsPqBaseLayer() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                videoFormat(
                    mimeType = MimeTypes.VIDEO_H265,
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

    @Test
    fun sdrTrackInAnotherGroupIsNotAnHdrFallback() {
        val hdrOnlyGroup = TrackGroup(
            videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
        )
        val unrelatedSdrGroup = TrackGroup(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
        )
        val hdrSupport = intArrayOf(primaryDecoderSupport)
        val sdrSupport = intArrayOf(primaryDecoderSupport)

        // Media3 TrackGroups represent alternatives for the same content. An SDR group for
        // another feed/camera angle must not cause the HDR-only group to be masked.
        assertFalse(hasSupportedSdrTrack(hdrOnlyGroup, hdrSupport))
        assertTrue(hasSupportedSdrTrack(unrelatedSdrGroup, sdrSupport))
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                hdrOnlyGroup.getFormat(0),
                hdrSupport[0],
                emptySet(),
                hasSdrFallback = hasSupportedSdrTrack(hdrOnlyGroup, hdrSupport),
            ),
        )
    }

    @Test
    fun sdrVariantInTheSameTrackGroupIsAValidHdrFallback() {
        val mixedRangeGroup = TrackGroup(
            videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
        )
        val support = intArrayOf(primaryDecoderSupport, primaryDecoderSupport)

        assertTrue(hasSupportedSdrTrack(mixedRangeGroup, support))
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                mixedRangeGroup.getFormat(0),
                support[0],
                emptySet(),
                hasSdrFallback = hasSupportedSdrTrack(mixedRangeGroup, support),
            ),
        )
    }

    @Test
    fun unsupportedHdrIsMaskedWhenAnotherSupportedHdrExistsWithoutSdr() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG),
                rendererSupportFlags = primaryDecoderSupport,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = false,
                hasSupportedHdrFallback = true,
            ),
        )
    }

    @Test
    fun unsupportedHdrRemainsWhenNoSupportedAlternativeExists() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                format = videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG),
                rendererSupportFlags = primaryDecoderSupport,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = false,
                hasSupportedHdrFallback = false,
            ),
        )
    }

    @Test
    fun wideGamutHighBitDepthWithUnknownTransferIsNotAnSdrFallback() {
        val ambiguousColorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setLumaBitdepth(10)
            .setChromaBitdepth(10)
            .build()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ambiguousColorInfo)
            .build()

        assertFalse(isSupportedSdrFallback(format, primaryDecoderSupport))
    }

    @Test
    fun unsetColorInfoRemainsAnAssumedSdrFallback() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertTrue(isSupportedSdrFallback(format, primaryDecoderSupport))
    }

    @Test
    fun explicitSdrTransferIsRecognizedForWideGamutTenBitContent() {
        val colorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .setLumaBitdepth(10)
            .setChromaBitdepth(10)
            .build()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(colorInfo)
            .build()

        assertTrue(isSupportedSdrFallback(format, primaryDecoderSupport))
    }

    @Test
    fun explicitGamma22TransferIsRecognizedAsSdrFallback() {
        val format = videoFormat(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_GAMMA_2_2)
        assertTrue(isSupportedSdrFallback(format, primaryDecoderSupport))
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
