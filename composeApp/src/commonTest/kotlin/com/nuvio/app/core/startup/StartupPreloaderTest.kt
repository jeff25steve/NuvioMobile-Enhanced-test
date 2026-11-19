package com.nuvio.app.core.startup

import com.nuvio.app.features.profiles.isProfileCacheLoadGenerationCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPreloaderTest {
    @Test
    fun `app gate loads cached profiles when platform startup preload did not run`() {
        assertTrue(shouldLoadProfileCacheFromAppGate(startupPreloadCompleted = false))
    }

    @Test
    fun `app gate does not repeat cached profile loading after startup preload`() {
        assertFalse(shouldLoadProfileCacheFromAppGate(startupPreloadCompleted = true))
    }
    @Test
    fun `profile cache load is accepted while its generation remains current`() {
        assertTrue(isProfileCacheLoadGenerationCurrent(expectedGeneration = 4L, currentGeneration = 4L))
    }

    @Test
    fun `profile cache load is rejected after in memory state is cleared`() {
        assertFalse(isProfileCacheLoadGenerationCurrent(expectedGeneration = 4L, currentGeneration = 5L))
    }

    @Test
    fun `independent startup reads overlap the ordered critical reads`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = mutableSetOf<String>()
        val gate = CompletableDeferred<Unit>()
        val sequentialStarted = CompletableDeferred<Unit>()

        val preload = async {
            runStartupPreloadWork(
                dispatcher = dispatcher,
                sequential = {
                    sequentialStarted.complete(Unit)
                    gate.await()
                },
                independent = listOf(
                    {
                        started += "continueWatching"
                        gate.await()
                    },
                    {
                        started += "homeSnapshot"
                        gate.await()
                    },
                ),
            )
        }

        runCurrent()

        assertEquals(setOf("continueWatching", "homeSnapshot"), started)
        assertTrue(sequentialStarted.isCompleted)

        gate.complete(Unit)
        preload.await()
    }

}
