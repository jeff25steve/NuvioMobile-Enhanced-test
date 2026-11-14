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


    @Test
    fun `queued download is paused before start when mobile data is disallowed`() {
        val item = sampleDownload().copy(
            status = DownloadStatus.Downloading,
            downloadedBytes = 4_096L,
            totalBytes = 16_384L,
            errorMessage = "Temporary error",
        )

        val paused = item.pauseIfNetworkDisallowed(
            networkAllowed = false,
            updatedAtEpochMs = 99L,
        )

        assertEquals(DownloadStatus.Paused, paused.status)
        assertEquals(4_096L, paused.downloadedBytes)
        assertEquals(16_384L, paused.totalBytes)
        assertNull(paused.errorMessage)
        assertEquals(item.sourceUrl, paused.sourceUrl)
        assertEquals(item.fileName, paused.fileName)
        assertEquals(99L, paused.updatedAtEpochMs)
    }

    @Test
    fun `queued download is unchanged before start when its network is allowed`() {
        val item = sampleDownload().copy(status = DownloadStatus.Downloading)

        assertEquals(
            item,
            item.pauseIfNetworkDisallowed(networkAllowed = true, updatedAtEpochMs = 99L),
        )
    }

    private fun sampleDownload() = DownloadItem(
        id = "network-policy",
        contentType = "movie",
        parentMetaId = "movie-1",
        parentMetaType = "movie",
        videoId = "movie-1",
        title = "Example",
        streamTitle = "Example stream",
        providerName = "Example provider",
        sourceUrl = "https://media.example/movie.mp4",
        fileName = "movie-1.mp4",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
