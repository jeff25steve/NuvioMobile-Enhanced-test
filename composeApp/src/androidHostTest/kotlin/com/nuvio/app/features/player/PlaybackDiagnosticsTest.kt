package com.nuvio.app.features.player

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticsTest {

    @Test
    fun firstManifestAndMediaEventsAreLimitedToOnePerAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimFirstLoad(C.DATA_TYPE_DRM))
    }

    @Test
    fun firstCompletionEventsAreLimitedToOnePerAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST))
        assertFalse(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun rebufferLoadsAreCapturedOncePerRebufferAfterPlaybackHasStarted() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)

        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))
        diagnostics.markPlaybackStarted()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MANIFEST))

        diagnostics.markReady()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))
        assertTrue(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA))
        assertFalse(diagnostics.claimRebufferLoadCompletion(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun beginningANewAttemptResetsRebufferDiagnosticState() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        diagnostics.markPlaybackStarted()
        diagnostics.markRebufferStarted()
        assertTrue(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))

        diagnostics.beginAttempt(250L)

        assertFalse(diagnostics.claimRebufferLoad(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun beginningANewAttemptResetsFirstLoadAndCompletionGuards() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(100L)
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA))

        diagnostics.beginAttempt(250L)

        assertEquals(2, diagnostics.attempt)
        assertEquals(250L, diagnostics.prepareStartedAtMs)
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MANIFEST))
        assertTrue(diagnostics.claimFirstLoad(C.DATA_TYPE_MEDIA))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MANIFEST))
        assertTrue(diagnostics.claimFirstCompletion(C.DATA_TYPE_MEDIA))
    }
}
