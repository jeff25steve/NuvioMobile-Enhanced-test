package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppReadinessTest {
    @Test
    fun `overlay fallback hides overlay without reporting main content ready`() {
        val rootContentReady = false
        val fallbackElapsed = true
        val overlayVisible = shouldShowMainLaunchOverlay(
            ownsAppRuntime = true,
            showLaunchOverlay = true,
            rootContentReady = rootContentReady,
            fallbackElapsed = fallbackElapsed,
        )

        assertFalse(overlayVisible)
        assertFalse(
            shouldReportMainContentReady(
                rootContentReady = rootContentReady,
                overlayIdle = true,
                overlayVisible = overlayVisible,
            ),
        )
    }

    @Test
    fun `main content reports ready only after readiness and overlay exit`() {
        assertTrue(
            shouldReportMainContentReady(
                rootContentReady = true,
                overlayIdle = true,
                overlayVisible = false,
            ),
        )
        assertFalse(
            shouldReportMainContentReady(
                rootContentReady = true,
                overlayIdle = false,
                overlayVisible = true,
            ),
        )
        assertFalse(
            shouldReportMainContentReady(
                rootContentReady = false,
                overlayIdle = true,
                overlayVisible = false,
            ),
        )
    }

    @Test
    fun `launch overlay fallback does not affect routes that do not own runtime`() {
        assertFalse(
            shouldShowMainLaunchOverlay(
                ownsAppRuntime = false,
                showLaunchOverlay = true,
                rootContentReady = false,
                fallbackElapsed = false,
            ),
        )
        assertFalse(
            shouldShowMainLaunchOverlay(
                ownsAppRuntime = true,
                showLaunchOverlay = false,
                rootContentReady = false,
                fallbackElapsed = false,
            ),
        )
    }
}
