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
}
