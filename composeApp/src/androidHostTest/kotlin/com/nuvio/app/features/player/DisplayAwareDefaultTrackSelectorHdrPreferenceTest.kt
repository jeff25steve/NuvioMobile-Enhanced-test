@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.view.Display
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplayAwareDefaultTrackSelectorHdrPreferenceTest {
    @Test
    fun hlsPqAndSdrVariantsKeepTheirDisplayAwareSelectionBoundary() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=6000000,VIDEO-RANGE=PQ,CODECS="hvc1.2.4.L153.B0",RESOLUTION=3840x2160,FRAME-RATE=23.976
            hdr_hevc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=6000000,VIDEO-RANGE=SDR,CODECS="hvc1.1.4.L153.B0",RESOLUTION=3840x2160,FRAME-RATE=23.976
            sdr_hevc.m3u8
        """.trimIndent()
        val playlist = HlsPlaylistParser().parse(
            Uri.parse("https://example.com/master.m3u8"),
            ByteArrayInputStream(manifest.toByteArray()),
        ) as HlsMultivariantPlaylist
        val formats = playlist.variants.map { it.format }

        assertEquals(2, formats.size)
        assertEquals(C.COLOR_TRANSFER_ST2084, formats[0].colorInfo?.colorTransfer)

        val group = TrackGroup(*formats.toTypedArray())
        val supports = IntArray(group.length) { C.FORMAT_HANDLED }
        val hdr10Display = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10)

        assertTrue(hasSupportedDisplayHdrTrack(group, supports, hdr10Display))
        assertTrue(hasSupportedSdrTrack(group, supports))
        assertTrue(
            shouldMaskSdrFormatForDisplay(
                format = formats[1],
                rendererSupportFlags = supports[1],
                hasSupportedHdrFallback = true,
            ),
        )
        // On an SDR-only display, keep the same SDR variant and mask the PQ alternative.
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = formats[0],
                rendererSupportFlags = supports[0],
                supportedHdrTypes = emptySet(),
                hasSdrFallback = true,
            ),
        )
        assertFalse(
            shouldMaskSdrFormatForDisplay(
                format = formats[1],
                rendererSupportFlags = supports[1],
                hasSupportedHdrFallback = false,
            ),
        )
    }

    @Test
    fun unknownHdrCapabilitiesRemainUnknownRatherThanBecomingSdrOnly() {
        assertNull(displayHdrTypesFromCapabilities(null))
        assertEquals(emptySet<Int>(), displayHdrTypesFromCapabilities(intArrayOf()))
        assertEquals(
            setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
            displayHdrTypesFromCapabilities(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10)),
        )
    }

    @Test
    fun supportedPqAlternativeIsRecognizedOnHdr10Display() {
        val group = TrackGroup(pqFormat(), sdrFormat())
        val supports = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED)

        assertTrue(
            hasSupportedDisplayHdrTrack(
                group,
                supports,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
            ),
        )
    }

    @Test
    fun supportedHdrAlternativeCausesSdrFallbackToBeMasked() {
        assertTrue(
            shouldMaskSdrFormatForDisplay(
                format = sdrFormat(),
                rendererSupportFlags = C.FORMAT_HANDLED,
                hasSupportedHdrFallback = true,
            ),
        )
    }

    @Test
    fun sdrFallbackIsNotMaskedWhenHdrDecoderIsUnsupported() {
        assertFalse(
            shouldMaskSdrFormatForDisplay(
                format = sdrFormat(),
                rendererSupportFlags = C.FORMAT_UNSUPPORTED_TYPE,
                hasSupportedHdrFallback = true,
            ),
        )
    }

    @Test
    fun unsupportedHdrIsMaskedWhenSdrFallbackExists() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = hlgFormat(),
                rendererSupportFlags = C.FORMAT_HANDLED,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun sdrFallbackRemainsWhenDisplayDoesNotSupportHdrType() {
        val group = TrackGroup(hlgFormat(), sdrFormat())
        val supports = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED)

        assertFalse(
            hasSupportedDisplayHdrTrack(
                group,
                supports,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
            ),
        )
        assertFalse(
            shouldMaskSdrFormatForDisplay(
                sdrFormat(),
                C.FORMAT_HANDLED,
                hasSupportedHdrFallback = false,
            ),
        )
    }

    @Test
    fun hdrOnlyContentRemainsSelectableWithoutSdrFallback() {
        assertFalse(
            shouldMaskHdrFormatForDisplay(
                format = pqFormat(),
                rendererSupportFlags = C.FORMAT_HANDLED,
                supportedHdrTypes = emptySet(),
                hasSdrFallback = false,
            ),
        )
    }

    @Test
    fun forceLowestBitrateBypassesDisplayHdrFiltering() {
        assertTrue(shouldApplyDisplayHdrFiltering(setOf(Display.HdrCapabilities.HDR_TYPE_HDR10), false))
        assertFalse(shouldApplyDisplayHdrFiltering(setOf(Display.HdrCapabilities.HDR_TYPE_HDR10), true))
        assertFalse(shouldApplyDisplayHdrFiltering(null, false))
    }

    @Test
    fun unsupportedHdrDecoderDoesNotSuppressSdrFallback() {
        val group = TrackGroup(pqFormat(), sdrFormat())
        val supports = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_HANDLED)

        assertFalse(
            hasSupportedDisplayHdrTrack(
                group,
                supports,
                setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
            ),
        )
    }

    @Test
    fun unsupportedHdrIsMaskedWhenAnotherSupportedHdrExistsWithoutSdr() {
        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = hlgFormat(),
                rendererSupportFlags = C.FORMAT_HANDLED,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = false,
                hasSupportedHdrFallback = true,
            ),
        )
    }

    @Test
    fun dolbyVisionProfile7HevcFallbackUsesHdr10BaseLayerDisplayCapability() {
        val profile7Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .build(),
            )
            .build()
        val hevcFallbackSupport = C.FORMAT_HANDLED or
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE

        assertFalse(
            shouldMaskHdrFormatForDisplay(
                format = profile7Format,
                rendererSupportFlags = hevcFallbackSupport,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun primaryDolbyVisionDecoderStillRequiresDolbyVisionDisplaySupport() {
        val profile7Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .build(),
            )
            .build()
        val primaryDolbyVisionSupport = C.FORMAT_HANDLED or
            RendererCapabilities.DECODER_SUPPORT_PRIMARY

        assertTrue(
            shouldMaskHdrFormatForDisplay(
                format = profile7Format,
                rendererSupportFlags = primaryDolbyVisionSupport,
                supportedHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10),
                hasSdrFallback = true,
            ),
        )
    }

    @Test
    fun ambiguousWideGamutColorMetadataIsNotClassifiedAsSdr() {
        val ambiguousFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .setLumaBitdepth(10)
                    .setChromaBitdepth(10)
                    .build(),
            )
            .build()
        val group = TrackGroup(ambiguousFormat)
        assertFalse(hasSupportedSdrTrack(group, intArrayOf(C.FORMAT_HANDLED)))
    }

    private fun pqFormat(): Format = hdrFormat(C.COLOR_TRANSFER_ST2084)

    private fun hlgFormat(): Format = hdrFormat(C.COLOR_TRANSFER_HLG)

    private fun hdrFormat(transfer: Int): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(transfer)
                    .build(),
            )
            .build()

    private fun sdrFormat(): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
}
