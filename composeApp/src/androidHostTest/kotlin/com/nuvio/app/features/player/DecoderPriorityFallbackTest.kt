package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecoderPriorityFallbackTest {

    @Test
    fun audioFailureChangesOnlyAudioRendererPriority() {
        val fallback = decoderPriorityFallbackForRendererFailure(
            failedRendererType = C.TRACK_TYPE_AUDIO,
            configuredVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            activeVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            configuredAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            activeAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            isDecoderFailure = true,
        )

        assertEquals(
            RendererDecoderPriorityFallback(
                trackType = C.TRACK_TYPE_AUDIO,
                priority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            ),
            fallback,
        )
    }

    @Test
    fun videoFailureChangesOnlyVideoRendererPriority() {
        val fallback = decoderPriorityFallbackForRendererFailure(
            failedRendererType = C.TRACK_TYPE_VIDEO,
            configuredVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            activeVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            configuredAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            activeAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            isDecoderFailure = true,
        )

        assertEquals(
            RendererDecoderPriorityFallback(
                trackType = C.TRACK_TYPE_VIDEO,
                priority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            ),
            fallback,
        )
    }

    @Test
    fun unknownRendererDoesNotChangeEitherTrackPriority() {
        assertNull(
            decoderPriorityFallbackForRendererFailure(
                failedRendererType = C.TRACK_TYPE_UNKNOWN,
                configuredVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                configuredAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                isDecoderFailure = true,
            ),
        )
    }

    @Test
    fun nonDecoderFailureDoesNotChangeEitherTrackPriority() {
        assertNull(
            decoderPriorityFallbackForRendererFailure(
                failedRendererType = C.TRACK_TYPE_AUDIO,
                configuredVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                configuredAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                isDecoderFailure = false,
            ),
        )
    }

    @Test
    fun alreadyOverriddenTrackCannotConsumeAnotherFallback() {
        assertNull(
            decoderPriorityFallbackForRendererFailure(
                failedRendererType = C.TRACK_TYPE_AUDIO,
                configuredVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeVideoPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                configuredAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                activeAudioPriority = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                isDecoderFailure = true,
            ),
        )
    }
}
