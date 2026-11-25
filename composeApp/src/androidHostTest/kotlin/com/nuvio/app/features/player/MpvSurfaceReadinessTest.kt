package com.nuvio.app.features.player

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MpvSurfaceReadinessTest {
    @Test
    fun successfulSurfaceAttachmentReleasesWaitingPlayback() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }

        readiness.complete()

        waitingPlayback.await()
    }

    @Test
    fun failedSurfaceAttachmentFailsWaitingPlaybackWithOriginalCause() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }
        val failure = IllegalStateException("surface attachment failed")

        readiness.fail(failure)

        val observed = assertFailsWith<IllegalStateException> {
            waitingPlayback.await()
        }
        assertSame(failure, observed)
    }

    @Test
    fun releasingPlayerCancelsWaitingPlayback() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }

        readiness.cancel()

        assertTrue(waitingPlayback.isCancelled)
    }
}
