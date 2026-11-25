package com.nuvio.app.features.player

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpvSurfaceReadinessTest {
    @Test
    fun invalidatedSurfaceGenerationCannotAttachAfterRecreation() {
        val generations = MpvSurfaceGeneration()
        val first = generations.begin()!!

        assertTrue(generations.invalidate(first))
        val second = generations.begin()!!

        assertTrue(!generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun staleDestroyCannotInvalidateNewerSurfaceGeneration() {
        val generations = MpvSurfaceGeneration()
        val first = generations.begin()!!
        val second = generations.begin()!!

        assertTrue(!generations.invalidate(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun releasingSurfaceGenerationsRejectsFutureAttachAndResizeWork() {
        val generations = MpvSurfaceGeneration()
        val active = generations.begin()!!

        generations.release()

        assertTrue(!generations.isCurrent(active))
        assertEquals(null, generations.begin())
        assertTrue(!generations.invalidate(active))
    }

    @Test
    fun videoOutputReleaseGateIgnoresReconfigWhileAndroidVoIsStillActive() {
        val gate = MpvVideoOutputReleaseGate()

        gate.onVideoReconfigured("gpu")

        assertTrue(!gate.isComplete())
    }

    @Test
    fun videoOutputReleaseGateCompletesOnlyAfterVoIsUnavailableOrNull() {
        val gate = MpvVideoOutputReleaseGate()

        gate.onVideoReconfigured("gpu")
        assertTrue(!gate.isComplete())

        gate.onVideoReconfigured(null)

        assertTrue(gate.isComplete())
    }

    @Test
    fun videoOutputReleaseGateAcceptsNullVoAsDetached() {
        val gate = MpvVideoOutputReleaseGate()

        gate.onVideoReconfigured("null")

        assertTrue(gate.isComplete())
    }

    @Test
    fun videoOutputReleaseGatePropagatesObserverReadFailure() = runTest {
        val gate = MpvVideoOutputReleaseGate()

        gate.fail(IllegalStateException("current-vo query failed"))

        assertFailsWith<IllegalStateException> {
            gate.await()
        }
    }

    @Test
    fun successfulSurfaceAttachmentReleasesWaitingPlayback() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }
        runCurrent()

        readiness.complete()

        waitingPlayback.await()
    }

    @Test
    fun failedSurfaceAttachmentPropagatesFailureMessage() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }
        runCurrent()
        val failure = IllegalStateException("surface attachment failed")

        readiness.fail(failure)

        val observed = assertFailsWith<IllegalStateException> {
            waitingPlayback.await()
        }
        assertEquals(failure.message, observed.message)
    }

    @Test
    fun releasingPlayerCancelsWaitingPlayback() = runTest {
        val readiness = MpvSurfaceReadiness()
        val waitingPlayback = async { readiness.await() }
        runCurrent()

        readiness.cancel()
        runCurrent()

        assertTrue(waitingPlayback.isCancelled)
    }
}
