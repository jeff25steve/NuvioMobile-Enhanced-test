package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationLiquidGlassEligibilityTest {
    @Test
    fun navigationMaterialRequiresItsOwnToggleBackdropAndPlatformCapabilities() {
        assertTrue(
            shouldEnableNavigationLiquidGlass(
                navBarEnabled = true,
                hasBackdrop = true,
                glassAvailable = true,
                refractionSupported = true,
                runtimeShaderSupported = true,
            ),
        )
        assertFalse(shouldEnableNavigationLiquidGlass(navBarEnabled = false))
        assertFalse(shouldEnableNavigationLiquidGlass(hasBackdrop = false))
        assertFalse(shouldEnableNavigationLiquidGlass(glassAvailable = false))
        assertFalse(shouldEnableNavigationLiquidGlass(refractionSupported = false))
        assertFalse(shouldEnableNavigationLiquidGlass(runtimeShaderSupported = false))
    }
}
