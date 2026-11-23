package com.nuvio.app.features.player

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplayAwareDefaultTrackSelectorHdrPreferenceTest {
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
