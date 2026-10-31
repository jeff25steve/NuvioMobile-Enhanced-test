package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeLiquidGlassControlsTest {
    @Test
    fun nativeLiquidGlassControlsRequireIos26OrNewer() {
        assertFalse(shouldUseNativeLiquidGlassControls(25L))
        assertTrue(shouldUseNativeLiquidGlassControls(26L))
        assertTrue(shouldUseNativeLiquidGlassControls(27L))
    }

    @Test
    fun liquidGlassSettingsRemainVisibleForNativeIosEvenWithoutComposeRenderer() {
        assertFalse(shouldExposeLiquidGlassSettings(false, false))
        assertTrue(shouldExposeLiquidGlassSettings(false, true))
        assertTrue(shouldExposeLiquidGlassSettings(true, false))
        assertTrue(shouldExposeLiquidGlassSettings(true, true))
    }

    @Test
    fun nativeGlassButtonsRequireEnabledRuntimeSurfaceAndNeverPreview() {
        assertFalse(shouldUseNativeLiquidGlassButton(25L, true, true, false))
        assertFalse(shouldUseNativeLiquidGlassButton(26L, false, true, false))
        assertFalse(shouldUseNativeLiquidGlassButton(26L, true, false, false))
        assertFalse(shouldUseNativeLiquidGlassButton(26L, true, true, true))
        assertTrue(shouldUseNativeLiquidGlassButton(26L, true, true, false))
        assertTrue(shouldUseNativeLiquidGlassButton(27L, true, true, false))
    }
}
