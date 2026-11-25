package com.nuvio.app.features.player

import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SoftwareVideoRendererOrderingTest {
    @Test
    fun forcedHardwareFailureFallbackClaimsFormatsTheSoftwareDecoderCanDecode() {
        assertTrue(
            shouldSoftwareVideoRendererHandleFormat(
                needsSoftwareDecoding = false,
                forceSoftwareDecoding = true,
            ),
        )
    }

    @Test
    fun normalPlaybackKeepsHardwarePreferredForCommonFormats() {
        assertFalse(
            shouldSoftwareVideoRendererHandleFormat(
                needsSoftwareDecoding = false,
                forceSoftwareDecoding = false,
            ),
        )
    }

    @Test
    fun intrinsicallySoftwareRequiredFormatsRemainHandledWithoutFallback() {
        assertTrue(
            shouldSoftwareVideoRendererHandleFormat(
                needsSoftwareDecoding = true,
                forceSoftwareDecoding = false,
            ),
        )
    }

    @Test
    fun preferModePlacesSoftwareRendererBeforeCoreAndOtherExtensionRenderers() {
        assertEquals(
            0,
            softwareVideoRendererInsertionIndex(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                rendererCount = 4,
            ),
        )
    }

    @Test
    fun onModeKeepsSoftwareRendererAfterExistingRenderers() {
        assertEquals(
            4,
            softwareVideoRendererInsertionIndex(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                rendererCount = 4,
            ),
        )
    }

    @Test
    fun offModeDoesNotRegisterSoftwareRenderer() {
        assertNull(
            softwareVideoRendererInsertionIndex(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                rendererCount = 4,
            ),
        )
    }
}
