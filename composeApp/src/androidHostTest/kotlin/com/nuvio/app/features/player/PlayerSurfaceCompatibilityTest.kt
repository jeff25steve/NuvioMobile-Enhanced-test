package com.nuvio.app.features.player

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSurfaceCompatibilityTest {
    @Test
    fun enablesComposeSurfaceSyncWorkaroundOnlyOnAndroid14() {
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(33))
        assertTrue(shouldEnableComposeSurfaceSyncWorkaround(34))
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(35))
        assertFalse(shouldEnableComposeSurfaceSyncWorkaround(36))
    }
}
