package com.nuvio.app.core.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPhaseCoordinatorTest {
    @Test
    fun awaitingCriticalPhaseDoesNotRunPostFrameWork() = runTest {
        val calls = mutableListOf<String>()
        val phases = StartupPhaseCoordinator(
            scope = backgroundScope,
            criticalPhase = { calls += "critical" },
            postFramePhase = { calls += "post-frame" },
        )
        assertFalse(phases.isCriticalReady)
        phases.awaitCriticalReady()
        assertTrue(phases.isCriticalReady)
        assertEquals(listOf("critical"), calls)
    }

    @Test
    fun concurrentCriticalWaitersShareOneInitialization() = runTest {
        val gate = CompletableDeferred<Unit>()
        var initializationCount = 0
        val phases = StartupPhaseCoordinator(
            scope = backgroundScope,
            criticalPhase = {
                initializationCount++
                gate.await()
            },
            postFramePhase = {},
        )
        val first = async { phases.awaitCriticalReady() }
        val second = async { phases.awaitCriticalReady() }
        runCurrent()
        assertEquals(1, initializationCount)
        gate.complete(Unit)
        first.await()
        second.await()
        assertTrue(phases.isCriticalReady)
    }

    @Test
    fun postFramePhaseWaitsForCriticalPhaseAndStartsOnlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val phases = StartupPhaseCoordinator(
            scope = backgroundScope,
            criticalPhase = {
                calls += "critical-start"
                gate.await()
                calls += "critical-finished"
            },
            postFramePhase = { calls += "post-frame" },
        )
        phases.startPostFrameWarmup()
        runCurrent()
        assertFalse(phases.isCriticalReady)
        assertEquals(listOf("critical-start"), calls)
        gate.complete(Unit)
        runCurrent()
        phases.startPostFrameWarmup()
        runCurrent()
        assertTrue(phases.isCriticalReady)
        assertEquals(listOf("critical-start", "critical-finished", "post-frame"), calls)
    }
}
