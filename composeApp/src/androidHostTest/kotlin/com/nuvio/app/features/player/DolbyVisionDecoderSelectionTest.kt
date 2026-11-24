package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DolbyVisionDecoderSelectionTest {
    @Test
    fun softMatchIsUsedOnlyForDolbyVisionOnDolbyVisionDisplay() {
        assertTrue(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_DOLBY_VISION,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_DOLBY_VISION,
                displaySupportsDolbyVision = false,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_H265,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                null,
                displaySupportsDolbyVision = true,
            ),
        )
    }

    @Test
    fun profile8PqSoftMatchRequiresDolbyVisionMimeDisplayAndNativeProfile8Codec() {
        assertTrue(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.08.06",
                C.COLOR_TRANSFER_ST2084,
                displaySupportsDolbyVision = true,
            ),
        )
        assertTrue(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvh1.08.06,mp4a.40.2",
                C.COLOR_TRANSFER_ST2084,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.08.06",
                C.COLOR_TRANSFER_HLG,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.07.06",
                C.COLOR_TRANSFER_ST2084,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_H265,
                "dvhe.08.06",
                C.COLOR_TRANSFER_ST2084,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldPromoteDolbyVisionProfile8Support(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.08.06",
                C.COLOR_TRANSFER_ST2084,
                displaySupportsDolbyVision = false,
            ),
        )
    }

    @Test
    fun profile8PqFormatDetectionDoesNotRequireDisplayLookup() {
        assertTrue(
            isDolbyVisionProfile8PqFormat(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.08.06",
                C.COLOR_TRANSFER_ST2084,
            ),
        )
        assertFalse(
            isDolbyVisionProfile8PqFormat(
                MimeTypes.VIDEO_DOLBY_VISION,
                "dvhe.08.06",
                C.COLOR_TRANSFER_HLG,
            ),
        )
        assertFalse(
            isDolbyVisionProfile8PqFormat(
                MimeTypes.VIDEO_H265,
                "dvhe.08.06",
                C.COLOR_TRANSFER_ST2084,
            ),
        )
    }

    @Test
    fun promotedCapabilitiesChangeOnlyFormatSupportAndOnlyWhenNativeDvExists() {
        val base = RendererCapabilities.create(
            C.FORMAT_EXCEEDS_CAPABILITIES,
            RendererCapabilities.ADAPTIVE_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY,
        )
        val promoted = promoteDolbyVisionSoftMatchCapabilities(
            base,
            hasNativeDolbyVisionDecoder = true,
        )
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(promoted))
        assertEquals(
            RendererCapabilities.getAdaptiveSupport(base),
            RendererCapabilities.getAdaptiveSupport(promoted),
        )
        assertEquals(
            RendererCapabilities.getTunnelingSupport(base),
            RendererCapabilities.getTunnelingSupport(promoted),
        )
        assertEquals(
            RendererCapabilities.getHardwareAccelerationSupport(base),
            RendererCapabilities.getHardwareAccelerationSupport(promoted),
        )
        assertEquals(
            RendererCapabilities.getDecoderSupport(base),
            RendererCapabilities.getDecoderSupport(promoted),
        )
        assertEquals(
            base,
            promoteDolbyVisionSoftMatchCapabilities(base, hasNativeDolbyVisionDecoder = false),
        )

        val drmUnsupported = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        assertEquals(
            drmUnsupported,
            promoteDolbyVisionSoftMatchCapabilities(drmUnsupported, hasNativeDolbyVisionDecoder = true),
        )
        val noDecoder = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertEquals(
            C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(
                promoteDolbyVisionSoftMatchCapabilities(noDecoder, hasNativeDolbyVisionDecoder = true),
            ),
        )
        val unsupportedType = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        assertEquals(
            unsupportedType,
            promoteDolbyVisionSoftMatchCapabilities(unsupportedType, hasNativeDolbyVisionDecoder = true),
        )
    }

    @Test
    fun trackLabelPrefersTheLocalizedMedia3Name() {
        assertEquals(
            "English (Stereo)",
            resolvePlaybackTrackLabel(
                preferred = "English (Stereo)",
                formatLabel = "Main audio",
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
    }

    @Test
    fun trackLabelFallsBackThroughAvailableFormatMetadata() {
        assertEquals(
            "Main audio",
            resolvePlaybackTrackLabel(
                preferred = " ",
                formatLabel = "Main audio",
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "en",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "ec-3",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "2",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = null,
                id = "2",
                index = 1,
            ),
        )
    }

    @Test
    fun trackLabelUsesStableOrdinalWhenAllMetadataIsMissing() {
        assertEquals(
            "4",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = null,
                id = null,
                index = 3,
            ),
        )
    }
}
