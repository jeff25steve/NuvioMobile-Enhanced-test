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


    @Test
    fun `active downloads pause when wifi is lost and mobile data is disabled`() {
        assertTrue(
            shouldPauseDownloadOnCurrentNetwork(
                allowMobileDataDownloads = false,
                isOnWifi = false,
            ),
        )
    }

    @Test
    fun `active downloads continue on wifi when mobile data is disabled`() {
        assertFalse(
            shouldPauseDownloadOnCurrentNetwork(
                allowMobileDataDownloads = false,
                isOnWifi = true,
            ),
        )
    }

    @Test
    fun `active downloads continue on cellular when mobile data is enabled`() {
        assertFalse(
            shouldPauseDownloadOnCurrentNetwork(
                allowMobileDataDownloads = true,
                isOnWifi = false,
            ),
        )
    }
}
