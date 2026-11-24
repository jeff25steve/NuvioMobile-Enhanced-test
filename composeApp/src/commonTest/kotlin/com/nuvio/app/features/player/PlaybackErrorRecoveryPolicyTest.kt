package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackErrorRecoveryPolicyTest {
    @Test
    fun unexpectedRuntimeErrorDoesNotTriggerSourceOrEngineRecovery() {
        val error = "Attempt to invoke virtual method clone() on a null object reference [ERROR_CODE_UNSPECIFIED]"

        assertFalse(shouldRefreshCredentialedPlaybackSource(error))
        assertFalse(shouldRetryServerSourceAfterPlaybackError(error))
        assertFalse(shouldTryNextSourceAfterPlaybackError(error))
        assertFalse(shouldFailoverPlaybackEngine(error))
        assertFalse(shouldRemoveFailedPlaybackSourceFromCache(error))
    }

    @Test
    fun signedUrlRefreshRequiresAnExpiredOrRejectedHttpStatus() {
        assertTrue(shouldRefreshCredentialedPlaybackSource("HTTP 401 Unauthorized [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertTrue(shouldRefreshCredentialedPlaybackSource("HTTP 403 Forbidden [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertTrue(shouldRefreshCredentialedPlaybackSource("HTTP 410 Gone [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertFalse(shouldRefreshCredentialedPlaybackSource("HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertFalse(shouldRefreshCredentialedPlaybackSource("HTTP 503 Unavailable [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertFalse(shouldRefreshCredentialedPlaybackSource("Network failed [ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]"))
        assertFalse(shouldRefreshCredentialedPlaybackSource("HTTP 503 Unavailable"))
    }

    @Test
    fun networkFailuresCanTryAnotherSourceWithoutInvalidatingTheCachedLinkOrSwitchingEngines() {
        val error = "Unable to connect [ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]"

        assertTrue(shouldRetryServerSourceAfterPlaybackError(error))
        assertTrue(shouldTryNextSourceAfterPlaybackError(error))
        assertFalse(shouldFailoverPlaybackEngine(error))
        assertFalse(shouldRemoveFailedPlaybackSourceFromCache(error))
    }

    @Test
    fun decoderAndRendererFailuresCanUseTheOtherEngine() {
        val decoderError = "Decoder initialization failed [ERROR_CODE_DECODER_INIT_FAILED]"
        val renderError = "Video frame processing failed [ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED]"

        assertTrue(shouldTryNextSourceAfterPlaybackError(decoderError))
        assertTrue(shouldFailoverPlaybackEngine(decoderError))
        assertFalse(shouldRemoveFailedPlaybackSourceFromCache(decoderError))
        assertTrue(shouldFailoverPlaybackEngine(renderError))
    }

    @Test
    fun unsupportedContainerCanTryAnotherSourceOrEngine() {
        val error = "Unsupported container [ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED]"

        assertTrue(shouldTryNextSourceAfterPlaybackError(error))
        assertTrue(shouldFailoverPlaybackEngine(error))
        assertFalse(shouldRemoveFailedPlaybackSourceFromCache(error))
    }

    @Test
    fun engineFailoverTakesPriorityOnlyWhenItCanHandleTheError() {
        val decoderError = "Decoder initialization failed [ERROR_CODE_DECODER_INIT_FAILED]"
        val networkError = "Unable to connect [ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]"
        val unexpectedError = "Unexpected runtime error [ERROR_CODE_UNSPECIFIED]"

        assertTrue(shouldDeferSourceFallbackToEngineFailover(true, false, decoderError))
        assertFalse(shouldDeferSourceFallbackToEngineFailover(true, false, networkError))
        assertFalse(shouldDeferSourceFallbackToEngineFailover(true, false, unexpectedError))
        assertFalse(shouldDeferSourceFallbackToEngineFailover(false, false, decoderError))
        assertFalse(shouldDeferSourceFallbackToEngineFailover(true, true, decoderError))
    }

    @Test
    fun legacyLibmpvMessagesKeepTheirExistingRecoveryBehavior() {
        val error = "mpv could not open the file"

        assertTrue(shouldRefreshCredentialedPlaybackSource(error))
        assertTrue(shouldRetryServerSourceAfterPlaybackError(error))
        assertTrue(shouldTryNextSourceAfterPlaybackError(error))
        assertTrue(shouldFailoverPlaybackEngine(error))
        assertTrue(shouldRemoveFailedPlaybackSourceFromCache(error))
    }

    @Test
    fun onlyClearlyStaleHttpSourcesAreRemovedFromTheReuseCache() {
        assertTrue(shouldRemoveFailedPlaybackSourceFromCache("HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertTrue(shouldRemoveFailedPlaybackSourceFromCache("HTTP 410 Gone [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertFalse(shouldRemoveFailedPlaybackSourceFromCache("HTTP 503 Unavailable [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
    }
}
