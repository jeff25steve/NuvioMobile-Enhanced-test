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
}
