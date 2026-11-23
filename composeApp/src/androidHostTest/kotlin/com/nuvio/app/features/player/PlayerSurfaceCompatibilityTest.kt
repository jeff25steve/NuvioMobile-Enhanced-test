package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSurfaceCompatibilityTest {
    @Test
    fun enablesComposeSurfaceSyncWorkaroundOnlyOnAndroid14() {
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(33))
        assertTrue(shouldEnableComposeSurfaceSyncWorkaround(34))
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(35))
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(36))
    }
}
