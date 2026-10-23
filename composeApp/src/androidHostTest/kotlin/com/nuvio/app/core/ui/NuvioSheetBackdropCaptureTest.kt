package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuvioSheetBackdropCaptureTest {
    @Test
    fun studioPreviewCapturesLocalBackdropWhenMasterSwitchIsOff() {
        assertTrue(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = false,
                needsPreviewBackdrop = true,
                needsRuntimeGlassBackdrop = false,
            ),
        )
    }

    @Test
    fun runtimeSheetStillRequiresMasterSwitch() {
        assertFalse(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = false,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = true,
            ),
        )
        assertTrue(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = true,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = true,
            ),
        )
    }

    @Test
    fun unsupportedPlatformNeverCapturesBackdropEvenForPreview() {
        assertFalse(
            shouldCaptureNuvioBackdrop(
                platformSupported = false,
                masterEnabled = false,
                needsPreviewBackdrop = true,
                needsRuntimeGlassBackdrop = false,
            ),
        )
        assertFalse(
            shouldCaptureNuvioBackdrop(
                platformSupported = false,
                masterEnabled = true,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = true,
            ),
        )
    }

    @Test
    fun activeNonSheetGlassSurfaceCapturesBackdropWhenGloballyEnabled() {
        assertTrue(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = true,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = true,
            ),
        )
    }

    @Test
    fun activeNonSheetGlassSurfaceDoesNotCaptureWhenGloballyDisabled() {
        assertFalse(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = false,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = true,
            ),
        )
    }

    @Test
    fun inactiveSheetWithoutPreviewDoesNotCaptureBackdrop() {
        assertFalse(
            shouldCaptureNuvioBackdrop(
                platformSupported = true,
                masterEnabled = true,
                needsPreviewBackdrop = false,
                needsRuntimeGlassBackdrop = false,
            ),
        )
    }
}
