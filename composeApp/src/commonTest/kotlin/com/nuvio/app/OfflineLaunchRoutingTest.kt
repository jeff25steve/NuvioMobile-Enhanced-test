package com.nuvio.app

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineLaunchRoutingTest {
    @Test
    fun offlineFallbackWaitsUntilDownloadManifestIsRestored() {
        assertFalse(shouldResolveInitialLaunchRoute(true, false, false, NetworkCondition.NoInternet))
        assertTrue(shouldResolveInitialLaunchRoute(true, false, true, NetworkCondition.NoInternet))
    }

    @Test
    fun launchRoutingWaitsUntilHomeAndNetworkStateAreReady() {
        assertFalse(shouldResolveInitialLaunchRoute(false, false, true, NetworkCondition.NoInternet))
        assertFalse(shouldResolveInitialLaunchRoute(true, false, true, NetworkCondition.Checking))
    }

    @Test
    fun completedLaunchDecisionIsNotRepeated() {
        assertFalse(shouldResolveInitialLaunchRoute(true, true, true, NetworkCondition.Online))
    }
}
