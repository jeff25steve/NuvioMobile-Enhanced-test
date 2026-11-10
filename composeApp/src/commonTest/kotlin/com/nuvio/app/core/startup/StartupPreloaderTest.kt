package com.nuvio.app.core.startup

import kotlin.test.Test
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

}
