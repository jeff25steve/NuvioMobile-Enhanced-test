package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadNetworkPolicyTest {
    @Test
    fun `requires wifi when mobile data is disabled`() {
        assertTrue(isDownloadNetworkAllowed(allowMobileDataDownloads = false, isOnWifi = true))
        assertFalse(isDownloadNetworkAllowed(allowMobileDataDownloads = false, isOnWifi = false))
    }

    @Test
    fun `allows either network when mobile data is enabled`() {
        assertTrue(isDownloadNetworkAllowed(allowMobileDataDownloads = true, isOnWifi = true))
        assertTrue(isDownloadNetworkAllowed(allowMobileDataDownloads = true, isOnWifi = false))
    }
}
