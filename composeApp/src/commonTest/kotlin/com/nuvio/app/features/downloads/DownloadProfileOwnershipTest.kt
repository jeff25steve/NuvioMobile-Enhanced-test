package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DownloadProfileOwnershipTest {
    @Test
    fun profileKeySeparatesTheSameDownloadIdAcrossAccounts() {
        val first = DownloadOperationKey(
            owner = DownloadProfileOwner(2, "account-one", "profile-two"),
            downloadId = "download-123",
        )
        val second = DownloadOperationKey(
            owner = DownloadProfileOwner(2, "account-two", "profile-two"),
            downloadId = "download-123",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun profileKeySeparatesADeletedAndRecreatedProfileAtTheSameIndex() {
        val deleted = DownloadOperationKey(
            owner = DownloadProfileOwner(3, "account-one", "old-profile-id"),
            downloadId = "download-123",
        )
        val recreated = DownloadOperationKey(
            owner = DownloadProfileOwner(3, "account-one", "new-profile-id"),
            downloadId = "download-123",
        )

        assertNotEquals(deleted, recreated)
    }

    @Test
    fun updateChangesOnlyTheMatchingDownloadAndPreservesListOrder() {
        val first = download("first")
        val target = download("target")
        val last = download("last")

        val updated = updateProfileDownloadItem(
            items = listOf(first, target, last),
            downloadId = "target",
        ) {
            it.copy(
                status = DownloadStatus.Completed,
                downloadedBytes = 42L,
                localFileUri = "file:///downloads/target.mp4",
            )
        }

        assertEquals(
            listOf(
                first,
                target.copy(
                    status = DownloadStatus.Completed,
                    downloadedBytes = 42L,
                    localFileUri = "file:///downloads/target.mp4",
                ),
                last,
            ),
            updated,
        )
    }

    @Test
    fun updateReturnsNullWhenDownloadIsAbsentOrAlreadyUnchanged() {
        val item = download("present")

        assertNull(updateProfileDownloadItem(listOf(item), "missing") { it })
        assertNull(updateProfileDownloadItem(listOf(item), "present") { it })
    }

    private fun download(id: String) = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = "tmdb:100",
        parentMetaType = "movie",
        videoId = "tmdb:100",
        title = "Example",
        streamTitle = "Example stream",
        providerName = "Example provider",
        sourceUrl = "https://media.example/video.mp4",
        fileName = "$id.mp4",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
