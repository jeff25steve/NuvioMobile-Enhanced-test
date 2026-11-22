package com.nuvio.app.features.player

import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoftwareVideoRendererOrderingTest {
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
